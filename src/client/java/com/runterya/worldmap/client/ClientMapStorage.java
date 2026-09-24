package com.runterya.worldmap.client;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Client-side persistent map storage, saved per server/world.
 * Works independently of whether the server has the mod installed.
 */
public class ClientMapStorage {

    private static String currentServerId = null;

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
        currentServerId = null;
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

    private static Path getRegionFile(int rx, int rz) {
        return getStorageDir().resolve("r." + rx + "." + rz + ".map");
    }

    /**
     * Save a single chunk's color data to disk.
     */
    public static void saveChunk(int chunkX, int chunkZ, int[] colors) {
        if (currentServerId == null) return;
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int lx = chunkX & 31;
        int lz = chunkZ & 31;
        int offset = (lz * 32 + lx) * 1024;

        Path file = getRegionFile(rx, rz);
        try {
            Files.createDirectories(file.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.seek(offset);
                ByteBuffer buf = ByteBuffer.allocate(1024);
                for (int color : colors) {
                    buf.putInt(color);
                }
                raf.write(buf.array());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Load all saved chunk data into ClientMapManager for the current server.
     */
    public static void loadAllIntoManager() {
        if (currentServerId == null) return;
        Path storageDir = getStorageDir();
        if (!Files.exists(storageDir)) return;

        try {
            Files.list(storageDir)
                .filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.map"))
                .forEach(ClientMapStorage::loadRegionFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadRegionFile(Path file) {
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
                        ClientMapManager.receiveUpdate(chunkX, chunkZ, colors);
                    }
                }
            }
        } catch (NumberFormatException | IOException e) {
            e.printStackTrace();
        }
    }
}
