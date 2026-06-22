package com.runterya.worldmap.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class WaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File WAYPOINTS_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "worldmap_waypoints.json");
    private static List<Waypoint> waypoints = new ArrayList<>();

    public static void load() {
        if (WAYPOINTS_FILE.exists()) {
            try (Reader reader = new FileReader(WAYPOINTS_FILE)) {
                Type listType = new TypeToken<ArrayList<Waypoint>>(){}.getType();
                List<Waypoint> loaded = GSON.fromJson(reader, listType);
                if (loaded != null) {
                    waypoints = loaded;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void save() {
        try (Writer writer = new FileWriter(WAYPOINTS_FILE)) {
            GSON.toJson(waypoints, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void addWaypoint(Waypoint wp) {
        waypoints.add(wp);
        save();
    }

    public static void removeWaypoint(Waypoint wp) {
        waypoints.remove(wp);
        save();
    }

    public static List<Waypoint> getWaypoints() {
        return waypoints;
    }
}
