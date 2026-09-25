package com.runterya.worldmap.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.runterya.worldmap.WorldMapMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reads each mod's declared dimension IDs that should use vertical map layers. */
public final class LayeredDimensions {
    private static final String RESOURCE_PATH = "wmap_layered_dimensions.json";
    private static final Set<String> DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static volatile boolean loaded;

    private LayeredDimensions() {}

    /** Checks a live level against all installed mods' declarations. */
    public static boolean contains(Level level) {
        return level != null && contains(level.dimension().identifier().toString());
    }

    /** Checks a dimension ID against all installed mods' declarations. */
    public static boolean contains(String dimensionId) {
        loadDeclarations();
        return DIMENSIONS.contains(dimensionId);
    }

    private static synchronized void loadDeclarations() {
        if (loaded) return;
        loaded = true;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            String path = "data/" + modId + "/" + RESOURCE_PATH;
            mod.findPath(path).ifPresent(file -> loadFile(modId, file));
        }
    }

    private static void loadFile(String modId, Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(reader);
            if (!json.isJsonArray()) {
                WorldMapMod.LOGGER.warn("Ignoring {}: expected a JSON array of dimension IDs", file);
                return;
            }
            json.getAsJsonArray().forEach(entry -> {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                    WorldMapMod.LOGGER.warn("Ignoring non-string dimension ID in {}", file);
                    return;
                }
                String value = entry.getAsString();
                Identifier id = Identifier.tryParse(value);
                if (id == null) {
                    WorldMapMod.LOGGER.warn("Ignoring invalid dimension ID '{}' in {}", value, file);
                    return;
                }
                DIMENSIONS.add(id.toString());
            });
            WorldMapMod.LOGGER.debug("Loaded layered dimension declarations for mod {}", modId);
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not read layered dimension declarations for mod {} from {}", modId, file, exception);
        }
    }
}
