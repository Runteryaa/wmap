package com.runterya.worldmap.client;

import com.runterya.worldmap.WorldMapMod;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "worldmap-client-storage");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean writeScheduled;

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
            scheduleWriteLocked();
        }
    }

    private static void scheduleWriteLocked() {
        if (writeScheduled) return;
        writeScheduled = true;
        writer.submit(ClientMapStorage::drainPendingWrites);
    }

    private static void drainPendingWrites() {
        Map<PendingKey, PendingWrite> batch;
        synchronized (pendingWrites) {
            batch = new HashMap<>(pendingWrites);
            pendingWrites.clear();
            writeScheduled = false;
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
                scheduleWriteLocked();
            }
        }
        synchronized (pendingWrites) {
            if (!pendingWrites.isEmpty()) scheduleWriteLocked();
        }
    }

    private static boolean writeRegion(RegionKey region, List<Map.Entry<PendingKey, PendingWrite>> entries) {
        Path file = region.root().resolve("dim_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(region.dimension().getBytes(StandardCharsets.UTF_8)))
            .resolve("r." + region.regionX() + "." + region.regionZ() + ".map");
        try {
            Files.createDirectories(file.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                for (Map.Entry<PendingKey, PendingWrite> entry : entries) {
                    int chunkX = (int) (entry.getKey().chunkKey() >> 32);
                    int chunkZ = (int) entry.getKey().chunkKey();
                    int offset = ((chunkZ & 31) * 32 + (chunkX & 31)) * 1024;
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
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
                synchronized (pendingWrites) {
                    if (!pendingWrites.isEmpty() && !writeScheduled) scheduleWriteLocked();
                }
            }).get(10, TimeUnit.SECONDS);
            writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Timed out waiting for pending map writes", exception);
        }
    }

    /**
     * Load all saved chunk data into ClientMapManager for the current server.
     */
    public static void loadAllIntoManager() {
        if (currentServerId == null) return;
        Path storageDir = getStorageDir();
        if (!Files.exists(storageDir)) return;

        try (Stream<Path> paths = Files.list(storageDir)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path) && name.matches("r\\.-?\\d+\\.-?\\d+\\.map")) {
                    // Legacy client files had no dimension key; preserve them as Overworld data.
                    loadRegionFile(path, "minecraft:overworld", Map.of());
                } else if (Files.isDirectory(path) && name.startsWith("dim_")) {
                    try {
                        String dimension = new String(Base64.getUrlDecoder().decode(name.substring(4)), StandardCharsets.UTF_8);
                        Map<Long, Set<UUID>> explorers = loadExplorers(path, dimension);
                        loadDimensionFiles(path, dimension, explorers);
                    } catch (IllegalArgumentException exception) {
                        WorldMapMod.LOGGER.warn("Ignoring map folder with invalid dimension key: {}", path);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadDimensionFiles(Path directory, String dimension, Map<Long, Set<UUID>> explorers) {
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.map"))
                .forEach(path -> loadRegionFile(path, dimension, explorers));
        } catch (IOException exception) {
            WorldMapMod.LOGGER.warn("Could not read map data for dimension {}", dimension, exception);
        }
    }

    private static void loadRegionFile(Path file, String dimension, Map<Long, Set<UUID>> explorers) {
        String name = file.getFileName().toString();
        // Parse r.X.Z.map
        String[] parts = name.replace(".map", "").split("\\.");
        if (parts.length < 3) return;
        try {
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                long fileLen = raf.length();
                for (int lz = 0; lz < 32; lz++) {
                    for (int lx = 0; lx < 32; lx++) {
                        int offset = (lz * 32 + lx) * 1024;
                        if (fileLen < offset + 1024) continue;
                        raf.seek(offset);
                        byte[] bytes = new byte[1024];
                        raf.readFully(bytes);
                        
                        ByteBuffer buf = ByteBuffer.wrap(bytes);
                        int[] colors = new int[256];
                        boolean empty = true;
                        for (int i = 0; i < 256; i++) {
                            colors[i] = buf.getInt();
                            if (colors[i] != 0) {
                                empty = false;
                            }
                        }
                        if (empty) continue;
                        int chunkX = (rx << 5) | lx;
                        int chunkZ = (rz << 5) | lz;
                        long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
                        Set<UUID> chunkExplorers = explorers.get(chunkKey);
                        if (chunkExplorers == null && currentServerId != null && currentServerId.startsWith("singleplayer_")
                            && Minecraft.getInstance().player != null) {
                            chunkExplorers = Set.of(Minecraft.getInstance().player.getUUID());
                        }
                        ClientMapManager.receiveUpdate(dimension, chunkX, chunkZ, colors,
                            chunkExplorers == null ? Set.of() : chunkExplorers);
                    }
                }
            }
        } catch (NumberFormatException | IOException e) {
            e.printStackTrace();
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
