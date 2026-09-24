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
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("worldmap.json");
    private static boolean openWaypointActionsOnLook = true;

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

    private static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            JsonObject config = new JsonObject();
            config.addProperty("openWaypointActionsOnLook", openWaypointActionsOnLook);
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                com.google.gson.GsonBuilder gson = new com.google.gson.GsonBuilder().setPrettyPrinting();
                gson.create().toJson(config, writer);
            }
        } catch (Exception exception) {
            WorldMapMod.LOGGER.warn("Could not save worldmap.json", exception);
        }
    }
}
