package com.runterya.worldmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.runterya.worldmap.backend.NetherMapView;
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
        ALL,
        SELECTED_PLAYER
    }

    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("worldmap.json");
    private static boolean openWaypointActionsOnLook = true;
    private static MapLayer mapLayer = MapLayer.ALL;
    private static String selectedExplorerUuid = "";
    private static String selectedExplorerName = "";
    private static boolean showPlayers = true;
    private static boolean showWaypoints = true;
    private static boolean showExploredAreas = true;
    private static NetherMapView netherMapView = NetherMapView.BEDROCK_SURFACE;

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
            if (config.has("selectedExplorerUuid") && config.get("selectedExplorerUuid").isJsonPrimitive()) {
                selectedExplorerUuid = config.get("selectedExplorerUuid").getAsString();
            }
            if (config.has("selectedExplorerName") && config.get("selectedExplorerName").isJsonPrimitive()) {
                selectedExplorerName = config.get("selectedExplorerName").getAsString();
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
            if (config.has("netherMapView") && config.get("netherMapView").isJsonPrimitive()) {
                try {
                    netherMapView = NetherMapView.valueOf(config.get("netherMapView").getAsString());
                } catch (IllegalArgumentException ignored) {
                    netherMapView = NetherMapView.BEDROCK_SURFACE;
                }
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
        mapLayer = switch (mapLayer) {
            case MY_EXPLORED -> MapLayer.OTHERS_EXPLORED;
            case OTHERS_EXPLORED -> MapLayer.ALL;
            case ALL, SELECTED_PLAYER -> MapLayer.MY_EXPLORED;
        };
        save();
        com.runterya.worldmap.client.ClientMapManager.refreshLayer();
    }

    public static String selectedExplorerUuid() {
        return selectedExplorerUuid;
    }

    public static String selectedExplorerName() {
        return selectedExplorerName;
    }

    public static void setSelectedExplorer(String uuid, String name) {
        selectedExplorerUuid = uuid == null ? "" : uuid;
        selectedExplorerName = name == null ? "" : name;
        mapLayer = MapLayer.SELECTED_PLAYER;
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

    public static NetherMapView netherMapView() {
        return netherMapView;
    }

    public static void toggleNetherMapView() {
        netherMapView = netherMapView == NetherMapView.BEDROCK_SURFACE
            ? NetherMapView.MID_LEVEL : NetherMapView.BEDROCK_SURFACE;
        save();
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
            config.addProperty("selectedExplorerUuid", selectedExplorerUuid);
            config.addProperty("selectedExplorerName", selectedExplorerName);
            config.addProperty("showPlayers", showPlayers);
            config.addProperty("showWaypoints", showWaypoints);
            config.addProperty("showExploredAreas", showExploredAreas);
            config.addProperty("netherMapView", netherMapView.name());
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                com.google.gson.GsonBuilder gson = new com.google.gson.GsonBuilder().setPrettyPrinting();
                gson.create().toJson(config, writer);
            }
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not save worldmap.json", exception);
        }
    }
}
