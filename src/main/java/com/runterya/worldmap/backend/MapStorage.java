package com.runterya.worldmap.backend;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

public class MapStorage {
    private final Path storageDir;
    // Cache of loaded chunk colors: ChunkPos long -> int[256]
    private final Map<Long, int[]> chunks = new ConcurrentHashMap<>();

    public MapStorage(Path storageDir) {
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void updateChunk(int chunkX, int chunkZ, int[] colors) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        chunks.put(key, colors);
        saveChunk(chunkX, chunkZ, colors);
    }

    public int[] getChunk(int chunkX, int chunkZ) {
        long key = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        int[] colors = chunks.get(key);
        if (colors == null) {
            colors = loadChunk(chunkX, chunkZ);
            if (colors != null) {
                chunks.put(key, colors);
            }
        }
        return colors;
    }

    private Path getRegionFile(int rx, int rz) {
        return storageDir.resolve("r." + rx + "." + rz + ".map");
    }

    private synchronized void saveChunk(int chunkX, int chunkZ, int[] colors) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int lx = chunkX & 31;
        int lz = chunkZ & 31;
        int offset = (lz * 32 + lx) * 1024;

        Path file = getRegionFile(rx, rz);
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

    private synchronized int[] loadChunk(int chunkX, int chunkZ) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int lx = chunkX & 31;
        int lz = chunkZ & 31;
        int offset = (lz * 32 + lx) * 1024;

        Path file = getRegionFile(rx, rz);
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
