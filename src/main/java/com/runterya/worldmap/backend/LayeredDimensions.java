package com.runterya.worldmap.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads each mod's declared dimension IDs that should use vertical map layers. */
public final class LayeredDimensions {
    private static final String RESOURCE_PATH = "wmap_layered_dimensions.json";
    private static final Set<String> DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> MAX_LAYER_Y = new ConcurrentHashMap<>();
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

    /** Highest grid-aligned slice at or below the configured inclusive Y ceiling. */
    public static int getMaxLayerY(String dimensionId, int minY, int maxY) {
        loadDeclarations();
        int worldMaxLayer = NetherMapView.getPlayerLayerY(maxY - 1, minY, maxY);
        int configuredMax = MAX_LAYER_Y.getOrDefault(dimensionId, worldMaxLayer);
        return Math.min(worldMaxLayer, NetherMapView.getPlayerLayerY(configuredMax, minY, maxY));
    }

    /** Quantize and clamp a requested slice to the configured visible range. */
    public static int clampLayerY(String dimensionId, int requestedY, int minY, int maxY) {
        int layerY = NetherMapView.getPlayerLayerY(requestedY, minY, maxY);
        return Math.min(layerY, getMaxLayerY(dimensionId, minY, maxY));
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
                String value;
                Integer maxLayerY = null;
                if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                    value = entry.getAsString();
                } else if (entry.isJsonObject()) {
                    JsonObject declaration = entry.getAsJsonObject();
                    JsonElement idElement = declaration.get("id");
                    if (idElement == null || !idElement.isJsonPrimitive()
                        || !idElement.getAsJsonPrimitive().isString()) {
                        WorldMapMod.LOGGER.warn("Ignoring layered dimension entry without a string 'id' in {}", file);
                        return;
                    }
                    value = idElement.getAsString();
                    JsonElement maxLayerElement = declaration.get("max_layer_y");
                    if (maxLayerElement != null) {
                        try {
                            maxLayerY = maxLayerElement.getAsInt();
                        } catch (RuntimeException exception) {
                            WorldMapMod.LOGGER.warn("Ignoring invalid max_layer_y for '{}' in {}", value, file);
                        }
                    }
                } else {
                    WorldMapMod.LOGGER.warn("Ignoring invalid layered dimension entry in {}", file);
                    return;
                }
                Identifier id = Identifier.tryParse(value);
                if (id == null) {
                    WorldMapMod.LOGGER.warn("Ignoring invalid dimension ID '{}' in {}", value, file);
                    return;
                }
                DIMENSIONS.add(id.toString());
                if (maxLayerY != null) MAX_LAYER_Y.put(id.toString(), maxLayerY);
            });
            WorldMapMod.LOGGER.debug("Loaded layered dimension declarations for mod {}", modId);
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not read layered dimension declarations for mod {} from {}", modId, file, exception);
        }
    }
}
