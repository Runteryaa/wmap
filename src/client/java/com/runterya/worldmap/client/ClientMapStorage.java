package com.runterya.worldmap.client;

import com.runterya.worldmap.WorldMapMod;
import com.runterya.worldmap.backend.LayeredDimensions;
import com.runterya.worldmap.backend.NetherMapView;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Comparator;

/**
 * Client-side persistent map storage, saved per server/world.
 * Works independently of whether the server has the mod installed.
 */
public class ClientMapStorage {

    private static String currentServerId = null;
    private static final Map<String, Map<Long, Set<UUID>>> explorerCache = new HashMap<>();
    private record PendingKey(Path root, String dimension, long chunkKey) {}
    private static final class PendingWrite {
        private int[] colors;
        private final Set<UUID> explorers = new HashSet<>();
    }
    private record RegionKey(Path root, String dimension, int regionX, int regionZ) {}
    private static final Map<PendingKey, PendingWrite> pendingWrites = new HashMap<>();
    private static final int MAX_WRITE_BATCH_CHUNKS = 512;
    private static final long WRITE_COALESCE_MILLIS = 100;
    private static final long WRITE_RETRY_MILLIS = 1_000;
    private static final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "worldmap-client-storage");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean writeScheduled;
    private static final int LOADED_CHUNK_QUEUE_SIZE = 256;
    private static final int LOADED_CHUNKS_PER_TICK = 24;
    private static final AtomicLong loadGeneration = new AtomicLong();
    private static final BlockingQueue<LoadedChunk> loadedChunks = new ArrayBlockingQueue<>(LOADED_CHUNK_QUEUE_SIZE);
    private static final java.util.concurrent.ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "worldmap-client-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService statisticsLoader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "worldmap-statistics-loader");
        thread.setDaemon(true);
        return thread;
    });
    private record LoadedChunk(long generation, String dimension, int chunkX, int chunkZ,
                               int[] colors, Set<UUID> explorers) {}
    private record RegionFile(Path path, String dimension, Path dimensionDirectory,
                              int regionX, int regionZ, int dimensionPriority,
                              long regionDistanceSquared) {}

    /**
     * Call on server join to set the server identifier.
     * Uses server IP for multiplayer and the save's canonical path for singleplayer.
     */
    public static void setCurrentServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) {
            // Multiplayer: use server address (sanitized)
            currentServerId = sanitize(mc.getCurrentServer().ip);
        } else if (mc.getSingleplayerServer() != null) {
            // Level names are user-editable and can be duplicated across saves.
            // Include a hash of the save path so each local world gets its own map.
            Path worldPath = mc.getSingleplayerServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath()
                .normalize();
            String worldName = worldPath.getFileName() == null ? "world" : worldPath.getFileName().toString();
            currentServerId = "singleplayer_" + sanitize(worldName) + "_" + hashWorldPath(worldPath);
        } else {
            currentServerId = "unknown";
        }
    }

    private static String hashWorldPath(Path worldPath) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(worldPath.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static void clearCurrentServer() {
        loadGeneration.incrementAndGet();
        loadedChunks.clear();
        flushPendingWrites();
        currentServerId = null;
        explorerCache.clear();
    }

    public static String getCurrentServerId() {
        return currentServerId;
    }

    /** Stable waypoint scope for the connected server or individual local save. */
    public static String getWaypointWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) {
            return "server_" + sanitize(mc.getCurrentServer().ip);
        }
        if (mc.getSingleplayerServer() != null) {
            String worldPath = mc.getSingleplayerServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath()
                .normalize()
                .toString();
            return "singleplayer_" + sanitize(worldPath);
        }
        return currentServerId == null ? "unknown" : currentServerId;
    }

    private static String sanitize(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static Path getStorageDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("worldmap")
                .resolve(currentServerId != null ? currentServerId : "unknown");
    }

    public static Path getCurrentStorageDirectory() {
        if (currentServerId == null || currentServerId.equals("unknown")) {
            throw new IllegalStateException("No world or server is currently selected");
        }
        return getStorageDir();
    }

    /** Scan saved color and explorer records off the client thread when the statistics screen is opened. */
    public static CompletableFuture<ExplorationStatistics> loadExplorationStatistics(UUID playerUuid) {
        if (currentServerId == null || currentServerId.equals("unknown")) {
            return CompletableFuture.failedFuture(new IllegalStateException("No current world map is selected"));
        }
        Path root = getStorageDir();
        boolean singleplayer = currentServerId.startsWith("singleplayer_");
        return CompletableFuture.supplyAsync(() -> {
            flushPendingWrites();
            return scanExplorationStatistics(root, playerUuid, singleplayer);
        }, statisticsLoader);
    }

    private static ExplorationStatistics scanExplorationStatistics(Path root, UUID playerUuid, boolean singleplayer) {
        Map<String, Set<Long>> chunksByDimension = new HashMap<>();
        Map<String, Set<Long>> playerChunksByDimension = new HashMap<>();
        if (!Files.isDirectory(root)) return new ExplorationStatistics(0, 0, Map.of(), Map.of());

        try (Stream<Path> dimensions = Files.list(root)) {
            List<Path> paths = dimensions.toList();
            List<Path> legacyRegions = paths.stream().filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.map"))
                .toList();
            if (!legacyRegions.isEmpty()) {
                Set<Long> legacyOverworld = chunksByDimension.computeIfAbsent("minecraft:overworld", ignored -> new HashSet<>());
                for (Path region : legacyRegions) addRegionChunks(region, legacyOverworld);
                if (singleplayer && playerUuid != null) {
                    playerChunksByDimension.computeIfAbsent("minecraft:overworld", ignored -> new HashSet<>())
                        .addAll(legacyOverworld);
                }
            }

            for (Path directory : paths.stream().filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith("dim_")).toList()) {
                String encoded = directory.getFileName().toString().substring(4);
                String dimension;
                try {
                    dimension = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    continue;
                }

                Set<Long> dimensionChunks = chunksByDimension.computeIfAbsent(dimension, ignored -> new HashSet<>());
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path region : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.map"))
                        .toList()) {
                        addRegionChunks(region, dimensionChunks);
                    }
                }

                if (playerUuid == null) continue;
                Set<Long> ownedChunks = playerChunksByDimension.computeIfAbsent(dimension, ignored -> new HashSet<>());
                Path explorerFile = directory.resolve("explorers.dat");
                if (Files.isRegularFile(explorerFile)) {
                    try (DataInputStream input = new DataInputStream(Files.newInputStream(explorerFile))) {
                        long remaining = Files.size(explorerFile);
                        while (remaining >= 24) {
                            long chunkKey = input.readLong();
                            UUID explorer = new UUID(input.readLong(), input.readLong());
                            if (playerUuid.equals(explorer)) ownedChunks.add(chunkKey);
                            remaining -= 24;
                        }
                    }
                }
                // Older single-player archives did not always store owner records. There is only one explorer there.
                if (singleplayer && ownedChunks.isEmpty()) ownedChunks.addAll(dimensionChunks);
            }
        } catch (IOException exception) {
            throw new java.util.concurrent.CompletionException(exception);
        }

        Map<String, Long> dimensionCounts = new HashMap<>();
        chunksByDimension.forEach((dimension, chunks) -> dimensionCounts.put(dimension, (long) chunks.size()));
        Map<String, Long> playerDimensionCounts = new HashMap<>();
        playerChunksByDimension.forEach((dimension, chunks) -> playerDimensionCounts.put(dimension, (long) chunks.size()));
        long totalChunks = dimensionCounts.values().stream().mapToLong(Long::longValue).sum();
        long playerChunks = playerDimensionCounts.values().stream().mapToLong(Long::longValue).sum();
        return new ExplorationStatistics(totalChunks, playerChunks, dimensionCounts, playerDimensionCounts);
    }

    private static void addRegionChunks(Path region, Set<Long> chunks) {
        String[] parts = region.getFileName().toString().replace(".map", "").split("\\.");
        if (parts.length != 3) return;
        try {
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
                long fileLength = file.length();
                byte[] bytes = new byte[1024];
                for (int slot = 0; slot < 1024; slot++) {
                    long offset = (long) slot * 1024;
                    if (fileLength < offset + 1024) break;
                    file.seek(offset);
                    file.readFully(bytes);
                    ByteBuffer colors = ByteBuffer.wrap(bytes);
                    boolean discovered = false;
                    for (int pixel = 0; pixel < 256; pixel++) {
                        if (colors.getInt() != 0) discovered = true;
                    }
                    if (discovered) {
                        int chunkX = (regionX << 5) | (slot & 31);
                        int chunkZ = (regionZ << 5) | (slot >> 5);
                        chunks.add((((long) chunkX) << 32) | (chunkZ & 0xffffffffL));
                    }
                }
            }
        } catch (IOException | NumberFormatException exception) {
            WorldMapMod.LOGGER.warn("Could not scan map region for statistics: {}", region, exception);
        }
    }

    private static Path getDimensionDir(String dimension) {
        String encodedDimension = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(dimension.getBytes(StandardCharsets.UTF_8));
        return getStorageDir().resolve("dim_" + encodedDimension);
    }

    private static Path getRegionFile(String dimension, int rx, int rz) {
        return getDimensionDir(dimension).resolve("r." + rx + "." + rz + ".map");
    }

    /**
     * Save a single chunk's color data to disk.
     */
    public static void saveChunk(String dimension, int chunkX, int chunkZ, int[] colors, Set<UUID> explorers) {
        saveChunk(dimension, chunkX, chunkZ, colors, explorers, true, true);
    }

    /** Queue changed map data; disk I/O is coalesced and performed off the render thread. */
    public static void saveChunk(String dimension, int chunkX, int chunkZ, int[] colors, Set<UUID> explorers,
                                 boolean saveColors, boolean saveExplorers) {
        if (currentServerId == null || (!saveColors && !saveExplorers)) return;
        Path root = getStorageDir();
        long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        PendingKey key = new PendingKey(root, dimension, chunkKey);
        synchronized (pendingWrites) {
            PendingWrite write = pendingWrites.computeIfAbsent(key, ignored -> new PendingWrite());
            if (saveColors && colors != null) write.colors = colors.clone();
            if (saveExplorers && explorers != null) write.explorers.addAll(explorers);
            scheduleWriteLocked(WRITE_COALESCE_MILLIS);
        }
    }

    private static void scheduleWriteLocked(long delayMillis) {
        if (writeScheduled) return;
        writeScheduled = true;
        writer.schedule(ClientMapStorage::drainPendingWrites, delayMillis, TimeUnit.MILLISECONDS);
    }

    private static void drainPendingWrites() {
        Map<PendingKey, PendingWrite> batch;
        synchronized (pendingWrites) {
            writeScheduled = false;
            batch = new HashMap<>(Math.min(MAX_WRITE_BATCH_CHUNKS, pendingWrites.size()));
            var iterator = pendingWrites.entrySet().iterator();
            while (batch.size() < MAX_WRITE_BATCH_CHUNKS && iterator.hasNext()) {
                Map.Entry<PendingKey, PendingWrite> entry = iterator.next();
                batch.put(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
        Map<RegionKey, List<Map.Entry<PendingKey, PendingWrite>>> byRegion = new HashMap<>();
        Map<Path, Map<String, List<Map.Entry<PendingKey, PendingWrite>>>> byStorage = new HashMap<>();
        batch.forEach((key, value) -> {
            if (value.colors != null) {
                int chunkX = (int) (key.chunkKey() >> 32);
                int chunkZ = (int) key.chunkKey();
                RegionKey region = new RegionKey(key.root(), key.dimension(), chunkX >> 5, chunkZ >> 5);
                byRegion.computeIfAbsent(region, ignored -> new ArrayList<>()).add(Map.entry(key, value));
            }
            if (!value.explorers.isEmpty()) {
                byStorage.computeIfAbsent(key.root(), ignored -> new HashMap<>())
                    .computeIfAbsent(key.dimension(), ignored -> new ArrayList<>()).add(Map.entry(key, value));
            }
        });
        Set<PendingKey> failedColors = new HashSet<>();
        byRegion.forEach((region, entries) -> {
            if (!writeRegion(region, entries)) entries.forEach(entry -> failedColors.add(entry.getKey()));
        });
        Set<PendingKey> failedExplorers = new HashSet<>();
        byStorage.forEach((root, dimensions) -> dimensions.forEach((dimension, entries) -> {
            if (!saveExplorerBatch(root, dimension, entries)) entries.forEach(entry -> failedExplorers.add(entry.getKey()));
        }));
        if (!failedColors.isEmpty() || !failedExplorers.isEmpty()) {
            synchronized (pendingWrites) {
                for (PendingKey key : failedColors) {
                    PendingWrite source = batch.get(key);
                    PendingWrite retry = pendingWrites.computeIfAbsent(key, ignored -> new PendingWrite());
                    if (retry.colors == null) retry.colors = source.colors;
                }
                for (PendingKey key : failedExplorers) {
                    PendingWrite source = batch.get(key);
                    pendingWrites.computeIfAbsent(key, ignored -> new PendingWrite()).explorers.addAll(source.explorers);
                }
            }
        }
        synchronized (pendingWrites) {
            if (!pendingWrites.isEmpty()) {
                scheduleWriteLocked(failedColors.isEmpty() && failedExplorers.isEmpty()
                    ? WRITE_COALESCE_MILLIS : WRITE_RETRY_MILLIS);
            }
        }
    }

    private static boolean writeRegion(RegionKey region, List<Map.Entry<PendingKey, PendingWrite>> entries) {
        Path file = region.root().resolve("dim_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(region.dimension().getBytes(StandardCharsets.UTF_8)))
            .resolve("r." + region.regionX() + "." + region.regionZ() + ".map");
        try {
            Files.createDirectories(file.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                for (Map.Entry<PendingKey, PendingWrite> entry : entries) {
                    int chunkX = (int) (entry.getKey().chunkKey() >> 32);
                    int chunkZ = (int) entry.getKey().chunkKey();
                    int offset = ((chunkZ & 31) * 32 + (chunkX & 31)) * 1024;
                    buffer.clear();
                    for (int color : entry.getValue().colors) buffer.putInt(color);
                    raf.seek(offset);
                    raf.write(buffer.array());
                }
            }
            return true;
        } catch (IOException exception) {
            WorldMapMod.LOGGER.warn("Could not save map region {}", file, exception);
            return false;
        }
    }

    private static boolean saveExplorerBatch(Path root, String dimension,
            List<Map.Entry<PendingKey, PendingWrite>> entries) {
        Map<Long, Set<UUID>> byChunk = explorerCache.computeIfAbsent(root + "|" + dimension,
            ignored -> loadExplorers(root.resolve("dim_" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(dimension.getBytes(StandardCharsets.UTF_8))), dimension));
        List<long[]> records = new ArrayList<>();
        Map<Long, Set<UUID>> additions = new HashMap<>();
        for (Map.Entry<PendingKey, PendingWrite> entry : entries) {
            long chunkKey = entry.getKey().chunkKey();
            Set<UUID> known = byChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>());
            Set<UUID> queued = additions.computeIfAbsent(chunkKey, ignored -> new HashSet<>());
            for (UUID explorer : entry.getValue().explorers) {
                if (!known.contains(explorer) && queued.add(explorer)) {
                    records.add(new long[] {chunkKey, explorer.getMostSignificantBits(), explorer.getLeastSignificantBits()});
                }
            }
        }
        if (records.isEmpty()) return true;
        Path file = root.resolve("dim_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(dimension.getBytes(StandardCharsets.UTF_8))).resolve("explorers.dat");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
                for (long[] record : records) {
                    output.writeLong(record[0]);
                    output.writeLong(record[1]);
                    output.writeLong(record[2]);
                }
            }
            additions.forEach((chunkKey, ids) -> byChunk.get(chunkKey).addAll(ids));
            return true;
        } catch (IOException exception) {
            WorldMapMod.LOGGER.warn("Could not save map explorers for dimension {}", dimension, exception);
            return false;
        }
    }

    /** Wait for queued writes during disconnect/shutdown so data survives process exit. */
    public static void flushPendingWrites() {
        try {
            writer.submit(() -> {
                for (int attempt = 0; attempt < 16; attempt++) {
                    synchronized (pendingWrites) {
                        if (pendingWrites.isEmpty()) return;
                        writeScheduled = false;
                    }
                    drainPendingWrites();
                }
            }).get(10, TimeUnit.SECONDS);
            synchronized (pendingWrites) {
                if (!pendingWrites.isEmpty()) {
                    WorldMapMod.LOGGER.warn("Some map writes are still queued after disconnect flush: {} chunks",
                        pendingWrites.size());
                }
            }
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Timed out waiting for pending map writes", exception);
        }
    }

    /**
     * Load all saved chunk data into ClientMapManager for the current server.
     */
    public static void loadAllIntoManager() {
        if (currentServerId == null) return;
        Minecraft mc = Minecraft.getInstance();
        Path storageDir = getStorageDir();
        if (!Files.isDirectory(storageDir)) return;

        String activeDimension = mc.level == null ? "minecraft:overworld"
            : mc.level.dimension().identifier().toString();
        int centerChunkX = mc.player == null ? 0 : mc.player.chunkPosition().x();
        int centerChunkZ = mc.player == null ? 0 : mc.player.chunkPosition().z();
        int activeLayerY = mc.level != null && LayeredDimensions.contains(mc.level) && mc.player != null
            ? NetherMapView.getPlayerLayerY(mc.level.dimension().identifier().toString(),
                mc.player.blockPosition().getY(), mc.level.getMinY(), mc.level.getMaxY())
            : Integer.MIN_VALUE;
        UUID singleplayerOwner = mc.getSingleplayerServer() != null && mc.player != null
            ? mc.player.getUUID() : null;
        boolean singleplayer = currentServerId.startsWith("singleplayer_");
        long generation = loadGeneration.incrementAndGet();
        loadedChunks.clear();
        loader.execute(() -> loadSavedMaps(storageDir, activeDimension, centerChunkX, centerChunkZ,
            activeLayerY, singleplayer, singleplayerOwner, generation));
    }

    /** Apply a bounded number of decoded records each client tick, keeping join responsive. */
    public static void processLoadedChunks() {
        long generation = loadGeneration.get();
        for (int i = 0; i < LOADED_CHUNKS_PER_TICK; i++) {
            LoadedChunk chunk = loadedChunks.poll();
            if (chunk == null) return;
            if (chunk.generation() != generation) continue;
            ClientMapManager.receiveDiskUpdate(chunk.dimension(), chunk.chunkX(), chunk.chunkZ(),
                chunk.colors(), chunk.explorers());
        }
    }

    private static void loadSavedMaps(Path storageDir, String activeDimension, int centerChunkX, int centerChunkZ,
                                      int activeLayerY, boolean singleplayer, UUID singleplayerOwner, long generation) {
        try {
            List<RegionFile> files = new ArrayList<>();
            try (Stream<Path> paths = Files.list(storageDir)) {
                for (Path path : paths.toList()) {
                    String name = path.getFileName().toString();
                    if (Files.isRegularFile(path) && name.matches("r\\.-?\\d+\\.-?\\d+\\.map")) {
                        addRegionFile(files, path, "minecraft:overworld", null, activeDimension,
                            centerChunkX, centerChunkZ, activeLayerY, true);
                    } else if (Files.isDirectory(path) && name.startsWith("dim_")) {
                        try {
                            String dimension = new String(Base64.getUrlDecoder().decode(name.substring(4)), StandardCharsets.UTF_8);
                            try (Stream<Path> dimensionFiles = Files.list(path)) {
                                for (Path region : dimensionFiles.filter(Files::isRegularFile)
                                    .filter(file -> file.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.map"))
                                    .toList()) {
                                    addRegionFile(files, region, dimension, path, activeDimension,
                                        centerChunkX, centerChunkZ, activeLayerY, false);
                                }
                            }
                        } catch (IllegalArgumentException exception) {
                            WorldMapMod.LOGGER.warn("Ignoring map folder with invalid dimension key: {}", path);
                        }
                    }
                }
            }
            files.sort(Comparator.comparingInt(RegionFile::dimensionPriority)
                .thenComparingLong(RegionFile::regionDistanceSquared));

            Map<Path, Map<Long, Set<UUID>>> explorersByDimension = new HashMap<>();
            for (RegionFile file : files) {
                if (generation != loadGeneration.get()) return;
                Map<Long, Set<UUID>> explorers = file.dimensionDirectory() == null ? Map.of()
                    : explorersByDimension.computeIfAbsent(file.dimensionDirectory(),
                        directory -> loadExplorers(directory, file.dimension()));
                loadRegionFile(file, explorers, singleplayer, singleplayerOwner, centerChunkX, centerChunkZ, generation);
            }
        } catch (IOException exception) {
            WorldMapMod.LOGGER.warn("Could not enumerate saved map data in {}", storageDir, exception);
        }
    }

    private static void addRegionFile(List<RegionFile> files, Path path, String dimension, Path directory,
                                      String activeDimension, int centerChunkX, int centerChunkZ,
                                      int activeLayerY, boolean legacy) {
        String[] parts = path.getFileName().toString().replace(".map", "").split("\\.");
        if (parts.length != 3) return;
        try {
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            String gameDimension = legacy ? dimension : NetherMapView.gameDimension(dimension);
            int priority;
            if (gameDimension.equals(activeDimension)) {
                int layerY = NetherMapView.getCaveLayerY(dimension);
                if (layerY == activeLayerY || (!NetherMapView.isCaveLayerDimension(dimension) && activeLayerY == Integer.MIN_VALUE)) {
                    priority = 0;
                } else if (NetherMapView.isCaveLayerDimension(dimension)) {
                    priority = 1 + (activeLayerY == Integer.MIN_VALUE ? 0 : Math.abs(layerY - activeLayerY) / NetherMapView.CAVE_LAYER_STEP);
                } else {
                    priority = 1;
                }
            } else {
                priority = 100;
            }
            long regionCenterX = ((long) rx << 5) + 16;
            long regionCenterZ = ((long) rz << 5) + 16;
            long dx = regionCenterX - centerChunkX;
            long dz = regionCenterZ - centerChunkZ;
            files.add(new RegionFile(path, dimension, directory, rx, rz, priority, dx * dx + dz * dz));
        } catch (NumberFormatException ignored) {
            // Ignore malformed region filenames.
        }
    }

    private static void loadRegionFile(RegionFile region, Map<Long, Set<UUID>> explorers, boolean singleplayer,
                                       UUID singleplayerOwner, int centerChunkX, int centerChunkZ, long generation) {
        try (RandomAccessFile raf = new RandomAccessFile(region.path().toFile(), "r")) {
            long fileLen = raf.length();
            List<Integer> slots = new ArrayList<>(1024);
            for (int slot = 0; slot < 1024; slot++) slots.add(slot);
            slots.sort(Comparator.comparingLong(slot -> {
                int chunkX = (region.regionX() << 5) | (slot & 31);
                int chunkZ = (region.regionZ() << 5) | (slot >> 5);
                long dx = (long) chunkX - centerChunkX;
                long dz = (long) chunkZ - centerChunkZ;
                return dx * dx + dz * dz;
            }));
            byte[] bytes = new byte[1024];
            for (int slot : slots) {
                if (generation != loadGeneration.get()) return;
                int offset = slot * 1024;
                if (fileLen < offset + 1024) continue;
                raf.seek(offset);
                raf.readFully(bytes);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                int[] colors = new int[256];
                boolean empty = true;
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = buffer.getInt();
                    if (colors[i] != 0) empty = false;
                }
                if (empty) continue;
                int chunkX = (region.regionX() << 5) | (slot & 31);
                int chunkZ = (region.regionZ() << 5) | (slot >> 5);
                long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
                Set<UUID> chunkExplorers = explorers.get(chunkKey);
                if (chunkExplorers == null && singleplayer && singleplayerOwner != null) {
                    chunkExplorers = Set.of(singleplayerOwner);
                }
                LoadedChunk loaded = new LoadedChunk(generation, region.dimension(), chunkX, chunkZ, colors,
                    chunkExplorers == null ? Set.of() : Set.copyOf(chunkExplorers));
                while (generation == loadGeneration.get()) {
                    if (loadedChunks.offer(loaded, 100, TimeUnit.MILLISECONDS)) break;
                }
            }
        } catch (IOException exception) {
            WorldMapMod.LOGGER.warn("Could not read map region {}", region.path(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<Long, Set<UUID>> loadExplorers(Path directory, String dimension) {
        Map<Long, Set<UUID>> byChunk = new HashMap<>();
        Path file = directory.resolve("explorers.dat");
        if (!Files.exists(file)) return byChunk;
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            while (input.available() >= 24) {
                long chunkKey = input.readLong();
                UUID explorer = new UUID(input.readLong(), input.readLong());
                byChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(explorer);
            }
        } catch (IOException exception) {
            WorldMapMod.LOGGER.warn("Could not load map explorers for dimension {}", dimension, exception);
        }
        return byChunk;
    }
}
