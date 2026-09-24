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

/**
 * Client-side persistent map storage, saved per server/world.
 * Works independently of whether the server has the mod installed.
 */
public class ClientMapStorage {

    private static String currentServerId = null;
    private static final Map<String, Map<Long, Set<UUID>>> explorerCache = new HashMap<>();

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
    public static synchronized void saveChunk(String dimension, int chunkX, int chunkZ, int[] colors, Set<UUID> explorers) {
        if (currentServerId == null) return;
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int lx = chunkX & 31;
        int lz = chunkZ & 31;
        int offset = (lz * 32 + lx) * 1024;

        Path file = getRegionFile(dimension, rx, rz);
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
        saveExplorers(dimension, chunkX, chunkZ, explorers);
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

    private static void saveExplorers(String dimension, int chunkX, int chunkZ, Set<UUID> explorers) {
        if (explorers.isEmpty()) return;
        long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
        Map<Long, Set<UUID>> byChunk = explorerCache.computeIfAbsent(dimension,
            key -> loadExplorers(getDimensionDir(key), key));
        Set<UUID> known = byChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>());
        Set<UUID> newlyAdded = new HashSet<>(explorers);
        newlyAdded.removeAll(known);
        if (newlyAdded.isEmpty()) return;
        known.addAll(newlyAdded);
        Path file = getDimensionDir(dimension).resolve("explorers.dat");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
                for (UUID explorer : newlyAdded) {
                    output.writeLong(chunkKey);
                    output.writeLong(explorer.getMostSignificantBits());
                    output.writeLong(explorer.getLeastSignificantBits());
                }
            }
        } catch (IOException exception) {
            WorldMapMod.LOGGER.warn("Could not save map explorers for dimension {}", dimension, exception);
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
