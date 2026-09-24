package com.runterya.worldmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client-only settings for the world map. */
public final class WorldMapConfig {
    public enum MapLayer {
        MY_EXPLORED,
        OTHERS_EXPLORED,
        ALL
    }

    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("worldmap.json");
    private static boolean openWaypointActionsOnLook = true;
    private static MapLayer mapLayer = MapLayer.ALL;
    private static boolean showPlayers = true;
    private static boolean showWaypoints = true;
    private static boolean showExploredAreas = true;

    private WorldMapConfig() {}

    public static void load() {
        if (!Files.exists(CONFIG_FILE)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            if (config.has("openWaypointActionsOnLook") && config.get("openWaypointActionsOnLook").isJsonPrimitive()) {
                openWaypointActionsOnLook = config.get("openWaypointActionsOnLook").getAsBoolean();
            }
            if (config.has("mapLayer") && config.get("mapLayer").isJsonPrimitive()) {
                try {
                    mapLayer = MapLayer.valueOf(config.get("mapLayer").getAsString());
                } catch (IllegalArgumentException ignored) {
                    mapLayer = MapLayer.ALL;
                }
            }
            if (config.has("showPlayers") && config.get("showPlayers").isJsonPrimitive()) {
                showPlayers = config.get("showPlayers").getAsBoolean();
            }
            if (config.has("showWaypoints") && config.get("showWaypoints").isJsonPrimitive()) {
                showWaypoints = config.get("showWaypoints").getAsBoolean();
            }
            if (config.has("showExploredAreas") && config.get("showExploredAreas").isJsonPrimitive()) {
                showExploredAreas = config.get("showExploredAreas").getAsBoolean();
            }
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not read worldmap.json; using default client settings", exception);
        }
    }

    public static boolean openWaypointActionsOnLook() {
        return openWaypointActionsOnLook;
    }

    public static void setOpenWaypointActionsOnLook(boolean enabled) {
        openWaypointActionsOnLook = enabled;
        save();
    }

    public static MapLayer mapLayer() {
        return mapLayer;
    }

    public static void cycleMapLayer() {
        MapLayer[] layers = MapLayer.values();
        mapLayer = layers[(mapLayer.ordinal() + 1) % layers.length];
        save();
        com.runterya.worldmap.client.ClientMapManager.refreshLayer();
    }

    public static boolean showPlayers() {
        return showPlayers;
    }

    public static void toggleShowPlayers() {
        showPlayers = !showPlayers;
        save();
    }

    public static boolean showWaypoints() {
        return showWaypoints;
    }

    public static void toggleShowWaypoints() {
        showWaypoints = !showWaypoints;
        save();
    }

    public static boolean showExploredAreas() {
        return showExploredAreas;
    }

    public static void toggleShowExploredAreas() {
        showExploredAreas = !showExploredAreas;
        save();
        com.runterya.worldmap.client.ClientMapManager.refreshLayer();
    }

    private static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            JsonObject config = new JsonObject();
            config.addProperty("openWaypointActionsOnLook", openWaypointActionsOnLook);
            config.addProperty("mapLayer", mapLayer.name());
            config.addProperty("showPlayers", showPlayers);
            config.addProperty("showWaypoints", showWaypoints);
            config.addProperty("showExploredAreas", showExploredAreas);
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                com.google.gson.GsonBuilder gson = new com.google.gson.GsonBuilder().setPrettyPrinting();
                gson.create().toJson(config, writer);
            }
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not save worldmap.json", exception);
        }
    }
}
