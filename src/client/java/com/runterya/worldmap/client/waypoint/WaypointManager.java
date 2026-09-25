package com.runterya.worldmap.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import com.runterya.worldmap.client.ClientMapStorage;
import com.runterya.worldmap.network.AddGlobalWaypointPayload;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class WaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File WAYPOINTS_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "worldmap_waypoints.json");
    private static List<Waypoint> waypoints = new ArrayList<>();
    private static List<Waypoint> globalWaypoints = new ArrayList<>();
    private static boolean serverWaypointSharingAvailable;

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
        if (wp.isGlobal() && serverWaypointSharingAvailable) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new AddGlobalWaypointPayload(
                AddGlobalWaypointPayload.Action.ADD, "", wp.getName(), wp.getX(), wp.getY(), wp.getZ(), wp.getColor(),
                wp.getDimension(), wp.getIcon()
            ));
            return;
        }

        wp.setGlobal(false);
        wp.setWorldId(ClientMapStorage.getWaypointWorldId());
        waypoints.add(wp);
        save();
    }

    public static void removeWaypoint(Waypoint wp) {
        if (wp.isGlobal()) {
            if (serverWaypointSharingAvailable && !wp.getGlobalId().isEmpty()) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new AddGlobalWaypointPayload(
                    AddGlobalWaypointPayload.Action.REMOVE, wp.getGlobalId(), "", 0, 0, 0, 0, wp.getDimension()
                ));
            }
        } else {
            waypoints.remove(wp);
            save();
        }
    }

    /** Deletes this client's locally stored waypoints and asks the server to remove their public ones. */
    public static void deleteAllOwnedWaypoints() {
        waypoints.clear();
        save();

        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (serverWaypointSharingAvailable && player != null) {
            String ownerUuid = player.getUUID().toString();
            globalWaypoints.removeIf(waypoint -> ownerUuid.equals(waypoint.getCreatorUuid()));
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new AddGlobalWaypointPayload(
                AddGlobalWaypointPayload.Action.DELETE_OWNED, "", "", 0, 0, 0, 0, ""
            ));
        }
    }

    public static void updateWaypoint(Waypoint original, Waypoint updated) {
        if (original.isGlobal()) {
            if (updated.isGlobal()) {
                if (serverWaypointSharingAvailable && !original.getGlobalId().isEmpty()) {
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new AddGlobalWaypointPayload(
                        AddGlobalWaypointPayload.Action.UPDATE, original.getGlobalId(), updated.getName(), updated.getX(), updated.getY(),
                        updated.getZ(), updated.getColor(), updated.getDimension(), updated.getIcon()
                    ));
                }
                return;
            }
            if (serverWaypointSharingAvailable && !original.getGlobalId().isEmpty()) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new AddGlobalWaypointPayload(
                    AddGlobalWaypointPayload.Action.REMOVE, original.getGlobalId(), "", 0, 0, 0, 0, original.getDimension()
                ));
            }
            updated.setGlobal(false);
            updated.setWorldId(original.getWorldId());
            waypoints.add(updated);
            save();
            return;
        }
        if (updated.isGlobal() && serverWaypointSharingAvailable) {
            waypoints.remove(original);
            save();
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new AddGlobalWaypointPayload(
                AddGlobalWaypointPayload.Action.ADD, "", updated.getName(), updated.getX(), updated.getY(), updated.getZ(),
                updated.getColor(), updated.getDimension(), updated.getIcon()
            ));
            return;
        }
        int index = waypoints.indexOf(original);
        if (index >= 0) {
            updated.setGlobal(false);
            updated.setWorldId(original.getWorldId());
            waypoints.set(index, updated);
            save();
        }
    }

    public static void addGlobalWaypoint(Waypoint wp) {
        wp.setGlobal(true);
        wp.setWorldId(ClientMapStorage.getWaypointWorldId());
        globalWaypoints.add(wp);
    }

    public static void replaceGlobalWaypoints(List<Waypoint> syncedWaypoints) {
        globalWaypoints.clear();
        for (Waypoint waypoint : syncedWaypoints) {
            addGlobalWaypoint(waypoint);
        }
    }
    
    public static void clearGlobalWaypoints() {
        globalWaypoints.clear();
    }

    public static void applyGlobalWaypointOwners(
        List<com.runterya.worldmap.network.SyncGlobalWaypointOwnersPayload.WaypointOwner> owners
    ) {
        for (var owner : owners) {
            for (Waypoint waypoint : globalWaypoints) {
                if (!waypoint.getGlobalId().equals(owner.waypointId())) continue;
                waypoint.setCreatorUuid(owner.creatorUuid());
                waypoint.setCreatorName(owner.creatorName());
                break;
            }
        }
    }

    public static List<Waypoint> getWaypoints() {
        String currentWorldId = ClientMapStorage.getWaypointWorldId();
        List<Waypoint> all = new ArrayList<>(waypoints);
        all.addAll(globalWaypoints);
        all.removeIf(waypoint -> !waypoint.getWorldId().equals(currentWorldId));
        return all;
    }

    /** Assign older, unscoped local waypoints to the first world they are opened in. */
    public static void bindLegacyWaypointsToCurrentWorld() {
        String currentWorldId = ClientMapStorage.getWaypointWorldId();
        boolean changed = false;
        for (Waypoint waypoint : waypoints) {
            if (waypoint.getWorldId().isEmpty()) {
                waypoint.setWorldId(currentWorldId);
                changed = true;
            }
        }
        if (changed) save();
    }

    public static void setServerWaypointSharingAvailable(boolean available) {
        serverWaypointSharingAvailable = available;
    }

    public static boolean isServerWaypointSharingAvailable() {
        return serverWaypointSharingAvailable;
    }
}
