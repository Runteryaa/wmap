package com.runterya.worldmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.runterya.worldmap.backend.LayeredDimensions;
import com.runterya.worldmap.backend.NetherMapView;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client-only settings for the world map. */
public final class WorldMapConfig {
    public enum LanguagePreference {
        MINECRAFT,
        ENGLISH,
        TURKISH
    }

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
    private static int selectedNetherLayerY = Integer.MIN_VALUE;
    private static boolean autoNetherLayer = true;
    private static LanguagePreference languagePreference = LanguagePreference.MINECRAFT;

    private WorldMapConfig() {}

    public static void load() {
        if (!Files.exists(CONFIG_FILE)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            if (config.has("language") && config.get("language").isJsonPrimitive()) {
                try {
                    languagePreference = LanguagePreference.valueOf(config.get("language").getAsString());
                } catch (IllegalArgumentException ignored) {
                    languagePreference = LanguagePreference.MINECRAFT;
                }
            }
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
                    String savedView = config.get("netherMapView").getAsString();
                    // The previous fixed Y=40 mode becomes the player-following cave layer.
                    netherMapView = "MID_LEVEL".equals(savedView)
                        ? NetherMapView.CAVE_LAYER : NetherMapView.valueOf(savedView);
                } catch (IllegalArgumentException ignored) {
                    netherMapView = NetherMapView.BEDROCK_SURFACE;
                }
            }
            if (config.has("selectedNetherLayerY") && config.get("selectedNetherLayerY").isJsonPrimitive()) {
                try {
                    selectedNetherLayerY = config.get("selectedNetherLayerY").getAsInt();
                } catch (NumberFormatException ignored) {
                    selectedNetherLayerY = Integer.MIN_VALUE;
                }
            }
            if (config.has("autoNetherLayer") && config.get("autoNetherLayer").isJsonPrimitive()) {
                autoNetherLayer = config.get("autoNetherLayer").getAsBoolean();
            } else {
                // Older configs followed the player until a specific Y layer was selected.
                autoNetherLayer = selectedNetherLayerY == Integer.MIN_VALUE;
            }
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not read worldmap.json; using default client settings", exception);
        }
    }

    public static boolean openWaypointActionsOnLook() {
        return openWaypointActionsOnLook;
    }

    public static LanguagePreference languagePreference() {
        return languagePreference;
    }

    public static void cycleLanguagePreference() {
        languagePreference = switch (languagePreference) {
            case MINECRAFT -> LanguagePreference.ENGLISH;
            case ENGLISH -> LanguagePreference.TURKISH;
            case TURKISH -> LanguagePreference.MINECRAFT;
        };
        save();
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
            ? NetherMapView.CAVE_LAYER : NetherMapView.BEDROCK_SURFACE;
        save();
    }

    public static int selectedNetherLayerY(int minY, int maxY, int playerY) {
        int requestedY = autoNetherLayer || selectedNetherLayerY == Integer.MIN_VALUE
            ? playerY : selectedNetherLayerY;
        return NetherMapView.getPlayerLayerY(requestedY, minY, maxY);
    }

    public static int selectedNetherLayerY(String dimension, int minY, int maxY, int playerY) {
        int requestedY = autoNetherLayer || selectedNetherLayerY == Integer.MIN_VALUE
            ? playerY : selectedNetherLayerY;
        return LayeredDimensions.clampLayerY(dimension, requestedY, minY, maxY);
    }

    public static boolean isNetherLayerAuto() {
        return autoNetherLayer;
    }

    public static void selectNetherAutoLayer() {
        autoNetherLayer = true;
        netherMapView = NetherMapView.CAVE_LAYER;
        save();
        com.runterya.worldmap.client.ClientMapManager.queueLoadedChunks();
    }

    public static void selectNetherCaveLayer(int layerY) {
        selectedNetherLayerY = layerY;
        autoNetherLayer = false;
        netherMapView = NetherMapView.CAVE_LAYER;
        save();
        com.runterya.worldmap.client.ClientMapManager.queueLoadedChunks();
    }

    public static void selectNetherBedrockTop() {
        netherMapView = NetherMapView.BEDROCK_SURFACE;
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
            config.addProperty("language", languagePreference.name());
            config.addProperty("openWaypointActionsOnLook", openWaypointActionsOnLook);
            config.addProperty("mapLayer", mapLayer.name());
            config.addProperty("selectedExplorerUuid", selectedExplorerUuid);
            config.addProperty("selectedExplorerName", selectedExplorerName);
            config.addProperty("showPlayers", showPlayers);
            config.addProperty("showWaypoints", showWaypoints);
            config.addProperty("showExploredAreas", showExploredAreas);
            config.addProperty("netherMapView", netherMapView.name());
            config.addProperty("autoNetherLayer", autoNetherLayer);
            if (selectedNetherLayerY != Integer.MIN_VALUE) {
                config.addProperty("selectedNetherLayerY", selectedNetherLayerY);
            }
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                com.google.gson.GsonBuilder gson = new com.google.gson.GsonBuilder().setPrettyPrinting();
                gson.create().toJson(config, writer);
            }
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not save worldmap.json", exception);
        }
    }
}
