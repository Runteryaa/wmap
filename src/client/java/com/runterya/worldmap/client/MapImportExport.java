package com.runterya.worldmap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable import/export for the current world's map and waypoints. */
public final class MapImportExport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type WAYPOINT_LIST_TYPE = new TypeToken<ArrayList<Waypoint>>() {}.getType();
    private static final Pattern MAP_ENTRY = Pattern.compile(
        "map/(dim_[A-Za-z0-9_-]+)/((?:r\\.-?\\d+\\.-?\\d+\\.map)|explorers\\.dat)"
    );
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_REGION_BYTES = 1024 * 1024;
    private static final int MAX_WAYPOINT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_EXPLORER_BYTES = 256 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024;

    private MapImportExport() {}

    public record ImportResult(List<Waypoint> waypoints, int regionFiles) {}

    public static void exportCurrentWorld(Path destination, List<Waypoint> currentWaypoints) throws IOException {
        Path mapRoot = ClientMapStorage.getCurrentStorageDirectory().toAbsolutePath().normalize();
        Path output = destination.toAbsolutePath().normalize();
        if (output.startsWith(mapRoot)) {
            throw new IOException("Choose an export location outside the map's storage folder");
        }

        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", "wmap-world-export");
        manifest.addProperty("version", FORMAT_VERSION);

        if (output.getParent() != null) Files.createDirectories(output.getParent());
        try (OutputStream fileOutput = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(fileOutput, StandardCharsets.UTF_8)) {
            addBytes(zip, "manifest.json", GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            addBytes(zip, "waypoints.json", GSON.toJson(currentWaypoints).getBytes(StandardCharsets.UTF_8));

            if (!Files.isDirectory(mapRoot)) return;
            try (Stream<Path> paths = Files.walk(mapRoot)) {
                for (Path file : paths.filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path)).toList()) {
                    String relative = mapRoot.relativize(file).toString().replace('\\', '/');
                    String entryName = relative.matches("r\\.-?\\d+\\.-?\\d+\\.map")
                        ? "map/dim_" + java.util.Base64.getUrlEncoder().withoutPadding()
                            .encodeToString("minecraft:overworld".getBytes(StandardCharsets.UTF_8)) + "/" + relative
                        : "map/" + relative;
                    if (!validMapEntry(entryName)) continue;
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    /** Validates the complete archive before merging its data into the current world. */
    public static ImportResult importIntoCurrentWorld(Path archive) throws IOException {
        List<Waypoint> importedWaypoints = validateArchive(archive);
        int regionFiles = 0;
        try (InputStream fileInput = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(fileInput, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("manifest.json") || name.equals("waypoints.json")) {
                    drain(zip, name.equals("manifest.json") ? MAX_METADATA_BYTES : MAX_WAYPOINT_BYTES);
                } else {
                    Matcher matcher = MAP_ENTRY.matcher(name);
                    if (!matcher.matches()) throw new IOException("Unexpected archive entry: " + name);
                    byte[] bytes = readBounded(zip, matcher.group(2).equals("explorers.dat")
                        ? MAX_EXPLORER_BYTES : MAX_REGION_BYTES);
                    String dimensionFolder = matcher.group(1);
                    String dimension = decodeDimensionFolder(dimensionFolder);
                    String dataFile = matcher.group(2);
                    if (dataFile.equals("explorers.dat")) {
                        ClientMapStorage.mergeImportedExplorers(dimension, decodeExplorerRecords(bytes));
                    } else {
                        ClientMapStorage.mergeImportedRegion(dimensionFolder, dataFile, bytes);
                        regionFiles++;
                    }
                }
                zip.closeEntry();
            }
        }

        return new ImportResult(List.copyOf(importedWaypoints), regionFiles);
    }

    private static List<Waypoint> validateArchive(Path archive) throws IOException {
        boolean hasManifest = false;
        boolean hasWaypoints = false;
        int entryCount = 0;
        long totalBytes = 0;
        List<Waypoint> waypoints = List.of();
        Set<String> names = new HashSet<>();

        try (InputStream fileInput = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(fileInput, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) throw new IOException("Folders are not valid archive entries");
                if (++entryCount > MAX_ENTRIES) throw new IOException("The archive contains too many files");
                String name = entry.getName();
                if (!names.add(name)) throw new IOException("Duplicate archive entry: " + name);

                byte[] data;
                if (name.equals("manifest.json")) {
                    hasManifest = true;
                    data = readBounded(zip, MAX_METADATA_BYTES);
                    validateManifest(data);
                } else if (name.equals("waypoints.json")) {
                    hasWaypoints = true;
                    data = readBounded(zip, MAX_WAYPOINT_BYTES);
                    waypoints = parseWaypoints(data);
                } else {
                    Matcher matcher = MAP_ENTRY.matcher(name);
                    if (!matcher.matches()) throw new IOException("Unexpected archive entry: " + name);
                    decodeDimensionFolder(matcher.group(1));
                    data = readBounded(zip, matcher.group(2).equals("explorers.dat")
                        ? MAX_EXPLORER_BYTES : MAX_REGION_BYTES);
                    if (matcher.group(2).equals("explorers.dat") && data.length % 24 != 0) {
                        throw new IOException("Invalid chunk ownership data");
                    }
                    if (matcher.group(2).endsWith(".map") && data.length % 1024 != 0) {
                        throw new IOException("Invalid map region data");
                    }
                }
                totalBytes += data.length;
                if (totalBytes > MAX_ARCHIVE_BYTES) throw new IOException("The archive is too large");
                zip.closeEntry();
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid WorldMap archive", exception);
        }

        if (!hasManifest || !hasWaypoints) {
            throw new IOException("This file is missing required WorldMap data");
        }
        return waypoints;
    }

    private static void validateManifest(byte[] bytes) throws IOException {
        JsonObject manifest = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!manifest.has("format") || !manifest.get("format").getAsString().equals("wmap-world-export")
            || !manifest.has("version") || manifest.get("version").getAsInt() != FORMAT_VERSION) {
            throw new IOException("Unsupported WorldMap archive format");
        }
    }

    private static List<Waypoint> parseWaypoints(byte[] bytes) throws IOException {
        List<Waypoint> waypoints = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), WAYPOINT_LIST_TYPE);
        if (waypoints == null || waypoints.size() > 100_000) throw new IOException("Invalid waypoint data");
        for (Waypoint waypoint : waypoints) {
            if (waypoint == null || waypoint.getName() == null || waypoint.getName().length() > 256
                || waypoint.getDimension() == null || waypoint.getDimension().length() > 256) {
                throw new IOException("Invalid waypoint entry");
            }
        }
        return waypoints;
    }

    private static Map<Long, Set<UUID>> decodeExplorerRecords(byte[] bytes) throws IOException {
        if (bytes.length % 24 != 0) throw new IOException("Invalid chunk ownership data");
        Map<Long, Set<UUID>> explorers = new HashMap<>();
        try (java.io.DataInputStream input = new java.io.DataInputStream(new ByteArrayInputStream(bytes))) {
            while (input.available() >= 24) {
                long chunkKey = input.readLong();
                UUID playerId = new UUID(input.readLong(), input.readLong());
                explorers.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(playerId);
            }
        }
        return explorers;
    }

    private static String decodeDimensionFolder(String dimensionFolder) throws IOException {
        try {
            String dimension = new String(java.util.Base64.getUrlDecoder().decode(dimensionFolder.substring(4)), StandardCharsets.UTF_8);
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(dimension.getBytes(StandardCharsets.UTF_8));
            if (dimension.isBlank() || !dimensionFolder.equals("dim_" + encoded)) {
                throw new IOException("Invalid dimension folder");
            }
            return dimension;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid dimension folder", exception);
        }
    }

    private static boolean validMapEntry(String entryName) {
        return MAP_ENTRY.matcher(entryName).matches();
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maxBytes) throw new IOException("Archive entry exceeds its size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void drain(InputStream input, int maxBytes) throws IOException {
        readBounded(input, maxBytes);
    }

    private static void addBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
