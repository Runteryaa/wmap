package com.runterya.worldmap.backend;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import net.minecraft.world.level.ChunkPos;

public class MapStorage {
    private static final int MAX_CHUNKS_PER_FLUSH = 512;
    private static final int MAX_EXPLORERS_PER_FLUSH = 4096;
    private final Path storageDir;
    public record ExploredChunk(String dimension, int chunkX, int chunkZ, Set<UUID> explorers) {}
    private record DimensionChunkKey(String dimension, long chunkKey) {}
    // Cache of loaded chunk colors, isolated by dimension and chunk coordinates.
    private final Map<DimensionChunkKey, int[]> chunks = new ConcurrentHashMap<>();
    private final Map<DimensionChunkKey, Set<UUID>> explorers = new ConcurrentHashMap<>();
    private final Set<String> loadedExplorerDimensions = ConcurrentHashMap.newKeySet();
    private final Map<DimensionChunkKey, int[]> dirtyChunks = new ConcurrentHashMap<>();
    private final Queue<ExplorerRecord> dirtyExplorers = new ConcurrentLinkedQueue<>();
    private record ExplorerRecord(String dimension, long chunkKey, UUID playerId) {}
    private record RegionKey(String dimension, int regionX, int regionZ) {}

    public MapStorage(Path storageDir) {
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void updateChunk(String dimension, int chunkX, int chunkZ, int[] colors) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        DimensionChunkKey dimensionChunkKey = new DimensionChunkKey(dimension, key);
        int[] copy = colors.clone();
        chunks.put(dimensionChunkKey, copy);
        dirtyChunks.put(dimensionChunkKey, copy);
    }

    public synchronized int[] getChunk(String dimension, int chunkX, int chunkZ) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        DimensionChunkKey dimensionChunkKey = new DimensionChunkKey(dimension, key);
        int[] colors = chunks.get(dimensionChunkKey);
        if (colors == null) {
            colors = loadChunk(dimension, chunkX, chunkZ);
            if (colors != null) {
                chunks.put(dimensionChunkKey, colors);
            }
        }
        return colors;
    }

    public synchronized Set<UUID> addExplorer(String dimension, int chunkX, int chunkZ, UUID playerId) {
        loadExplorers(dimension);
        long key = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        DimensionChunkKey dimensionChunkKey = new DimensionChunkKey(dimension, key);
        Set<UUID> chunkExplorers = explorers.computeIfAbsent(dimensionChunkKey,
            ignored -> ConcurrentHashMap.newKeySet());
        if (chunkExplorers.add(playerId)) {
            dirtyExplorers.add(new ExplorerRecord(dimension, key, playerId));
        }
        return Set.copyOf(chunkExplorers);
    }

    /** Flush accumulated map updates, grouping color writes by region file. */
    public synchronized void flushPending() {
        Map<RegionKey, List<Map.Entry<DimensionChunkKey, int[]>>> byRegion = new java.util.HashMap<>();
        int chunkCount = 0;
        for (Map.Entry<DimensionChunkKey, int[]> entry : dirtyChunks.entrySet()) {
            if (chunkCount++ >= MAX_CHUNKS_PER_FLUSH) break;
            DimensionChunkKey key = entry.getKey();
            int chunkX = (int) (key.chunkKey() >> 32);
            int chunkZ = (int) key.chunkKey();
            byRegion.computeIfAbsent(new RegionKey(key.dimension(), chunkX >> 5, chunkZ >> 5),
                ignored -> new ArrayList<>()).add(Map.entry(key, entry.getValue()));
        }
        byRegion.forEach((region, entries) -> {
            if (saveRegionChunks(region, entries)) {
                for (Map.Entry<DimensionChunkKey, int[]> entry : entries) {
                    dirtyChunks.remove(entry.getKey(), entry.getValue());
                }
            }
        });

        Map<String, List<ExplorerRecord>> byDimension = new java.util.HashMap<>();
        ExplorerRecord explorer;
        int explorerCount = 0;
        while (explorerCount++ < MAX_EXPLORERS_PER_FLUSH && (explorer = dirtyExplorers.poll()) != null) {
            byDimension.computeIfAbsent(explorer.dimension(), ignored -> new ArrayList<>()).add(explorer);
        }
        byDimension.forEach((dimension, records) -> {
            if (!saveExplorerRecords(dimension, records)) dirtyExplorers.addAll(records);
        });
    }

    public synchronized boolean hasPendingWrites() {
        return !dirtyChunks.isEmpty() || !dirtyExplorers.isEmpty();
    }

    private boolean saveRegionChunks(RegionKey region, List<Map.Entry<DimensionChunkKey, int[]>> entries) {
        Path file = getRegionFile(region.dimension(), region.regionX(), region.regionZ());
        try {
            Files.createDirectories(file.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                for (Map.Entry<DimensionChunkKey, int[]> entry : entries) {
                    int chunkX = (int) (entry.getKey().chunkKey() >> 32);
                    int chunkZ = (int) entry.getKey().chunkKey();
                    raf.seek(((long) ((chunkZ & 31) * 32 + (chunkX & 31))) * 1024L);
                    buffer.clear();
                    for (int color : entry.getValue()) buffer.putInt(color);
                    raf.write(buffer.array());
                }
            }
            return true;
        } catch (IOException exception) {
            exception.printStackTrace();
            return false;
        }
    }

    private boolean saveExplorerRecords(String dimension, List<ExplorerRecord> records) {
        Path file = getDimensionDirectory(dimension).resolve("explorers.dat");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
                for (ExplorerRecord record : records) {
                    output.writeLong(record.chunkKey());
                    output.writeLong(record.playerId().getMostSignificantBits());
                    output.writeLong(record.playerId().getLeastSignificantBits());
                }
            }
            return true;
        } catch (IOException exception) {
            exception.printStackTrace();
            return false;
        }
    }

    public synchronized Set<UUID> getExplorers(String dimension, int chunkX, int chunkZ) {
        loadExplorers(dimension);
        long key = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        return Set.copyOf(explorers.getOrDefault(new DimensionChunkKey(dimension, key), Set.of()));
    }

    public synchronized List<ExploredChunk> getDiscoveredChunks() {
        List<ExploredChunk> discovered = new ArrayList<>();
        try (var paths = Files.list(storageDir)) {
            for (Path directory : paths.filter(Files::isDirectory).toList()) {
                String name = directory.getFileName().toString();
                if (!name.startsWith("dim_")) continue;
                final String dimension;
                try {
                    dimension = new String(Base64.getUrlDecoder().decode(name.substring(4)), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                loadExplorers(dimension);
                for (Map.Entry<DimensionChunkKey, Set<UUID>> entry : explorers.entrySet()) {
                    DimensionChunkKey key = entry.getKey();
                    if (!key.dimension().equals(dimension) || entry.getValue().isEmpty()) continue;
                    int chunkX = (int) (key.chunkKey() >> 32);
                    int chunkZ = (int) key.chunkKey();
                    discovered.add(new ExploredChunk(dimension, chunkX, chunkZ, Set.copyOf(entry.getValue())));
                }
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }
        return discovered;
    }

    private void loadExplorers(String dimension) {
        if (!loadedExplorerDimensions.add(dimension)) return;
        Path file = getDimensionDirectory(dimension).resolve("explorers.dat");
        if (!Files.exists(file)) return;
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            while (input.available() >= 24) {
                long chunkKey = input.readLong();
                UUID playerId = new UUID(input.readLong(), input.readLong());
                explorers.computeIfAbsent(new DimensionChunkKey(dimension, chunkKey),
                    ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private Path getRegionFile(String dimension, int rx, int rz) {
        return getDimensionDirectory(dimension).resolve("r." + rx + "." + rz + ".map");
    }

    private Path getDimensionDirectory(String dimension) {
        String encodedDimension = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(dimension.getBytes(StandardCharsets.UTF_8));
        return storageDir.resolve("dim_" + encodedDimension);
    }

    private synchronized int[] loadChunk(String dimension, int chunkX, int chunkZ) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int lx = chunkX & 31;
        int lz = chunkZ & 31;
        int offset = (lz * 32 + lx) * 1024;

        Path file = getRegionFile(dimension, rx, rz);
        // Before dimensions were part of storage keys, server map regions lived
        // directly in the storage root. Treat those legacy files as Overworld.
        if (!Files.exists(file) && "minecraft:overworld".equals(dimension)) {
            Path legacyFile = storageDir.resolve("r." + rx + "." + rz + ".map");
            if (Files.exists(legacyFile)) file = legacyFile;
        }
        if (!Files.exists(file)) return null;

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() >= offset + 1024) {
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
                if (empty) return null;
                
                return colors;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
