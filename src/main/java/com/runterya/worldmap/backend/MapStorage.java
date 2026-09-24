package com.runterya.worldmap.backend;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

public class MapStorage {
    private final Path storageDir;
    private record DimensionChunkKey(String dimension, long chunkKey) {}
    // Cache of loaded chunk colors, isolated by dimension and chunk coordinates.
    private final Map<DimensionChunkKey, int[]> chunks = new ConcurrentHashMap<>();

    public MapStorage(Path storageDir) {
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void updateChunk(String dimension, int chunkX, int chunkZ, int[] colors) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        chunks.put(new DimensionChunkKey(dimension, key), colors.clone());
        saveChunk(dimension, chunkX, chunkZ, colors);
    }

    public int[] getChunk(String dimension, int chunkX, int chunkZ) {
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

    private Path getRegionFile(String dimension, int rx, int rz) {
        String encodedDimension = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(dimension.getBytes(StandardCharsets.UTF_8));
        return storageDir.resolve("dim_" + encodedDimension).resolve("r." + rx + "." + rz + ".map");
    }

    private synchronized void saveChunk(String dimension, int chunkX, int chunkZ, int[] colors) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int lx = chunkX & 31;
        int lz = chunkZ & 31;
        int offset = (lz * 32 + lx) * 1024;

        Path file = getRegionFile(dimension, rx, rz);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException exception) {
            exception.printStackTrace();
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(offset);
            ByteBuffer buf = ByteBuffer.allocate(1024);
            for (int color : colors) {
                buf.putInt(color);
            }
            raf.write(buf.array());
        } catch (IOException e) {
            e.printStackTrace();
        }
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
