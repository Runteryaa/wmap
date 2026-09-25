package com.runterya.worldmap.backend;

import com.runterya.worldmap.network.MapUpdatePayload;
import com.runterya.worldmap.network.MapColorReportPayload;
import com.runterya.worldmap.network.PlayerPosPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import com.runterya.worldmap.network.AddGlobalWaypointPayload;
import com.runterya.worldmap.network.SyncGlobalWaypointsPayload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldMapServer {
    public static final Set<UUID> MODDED_PLAYERS = ConcurrentHashMap.newKeySet();
    public static MapStorage storage;
    private static int tickCount = 0;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File globalWaypointsFile;
    private static final List<SyncGlobalWaypointsPayload.GlobalWaypoint> globalWaypoints = new ArrayList<>();

    /** Background thread for disk I/O so it doesn't block the server thread. */
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "worldmap-io");
        t.setDaemon(true);
        return t;
    });

    /** Track the previous ChunkTrackingView for each player to detect newly added chunks. */
    private static final java.util.Map<UUID, ChunkTrackingView> lastChunkView = new ConcurrentHashMap<>();
    private static final java.util.Map<UUID, String> lastPlayerDimension = new ConcurrentHashMap<>();

    /** Latest client-resolved colors; also serves joins while disk persistence is pending. */
    private record DimensionChunkKey(String dimension, long chunkKey) {}
    private static final java.util.Map<DimensionChunkKey, int[]> clientTintedChunks = new ConcurrentHashMap<>();

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }



    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(MapColorReportPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                if (!MODDED_PLAYERS.contains(context.player().getUUID()) || payload.colors().length != 256) {
                    return;
                }
                // Client biome color resources are authoritative for map tinting;
                // persist and share the vanilla-resolved colors it reports.
                String playerDimension = context.player().level().dimension().identifier().toString();
                String mapDimension = payload.dimension();
                if (!playerDimension.equals(NetherMapView.gameDimension(mapDimension))) return;
                if (NetherMapView.isCaveLayerDimension(mapDimension)) {
                    int layerY = NetherMapView.getCaveLayerY(mapDimension);
                    int minY = context.player().level().getMinY();
                    int maxY = context.player().level().getMaxY();
                    if (!NetherStyleDimensions.isNetherStyle(context.player().level())
                        || layerY < minY || layerY >= maxY || Math.floorMod(layerY, NetherMapView.CAVE_LAYER_STEP) != 0) {
                        return;
                    }
                }
                clientTintedChunks.put(new DimensionChunkKey(payload.dimension(), chunkKey(payload.chunkX(), payload.chunkZ())), payload.colors().clone());
                Set<UUID> explorers = storage == null
                    ? Set.of(context.player().getUUID())
                    : storage.addExplorer(payload.dimension(), payload.chunkX(), payload.chunkZ(), context.player().getUUID());
                if (storage != null) {
                    ioExecutor.submit(() -> storage.updateChunk(payload.dimension(), payload.chunkX(), payload.chunkZ(), payload.colors()));
                }
                broadcastMapUpdate(context.server(), payload.dimension(), payload.chunkX(), payload.chunkZ(), payload.colors(), explorers);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(com.runterya.worldmap.network.HandshakePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                MODDED_PLAYERS.add(context.player().getUUID());
                ServerPlayer player = context.player();

                // Restore all discovered shared chunks first, then fill any older
                // map-only chunks that have no ownership record from the current view.
                sendDiscoveredMaps(player);
                enqueuePlayerView(player, ChunkTrackingView.EMPTY, player.getChunkTrackingView());
                lastChunkView.put(player.getUUID(), player.getChunkTrackingView());
                lastPlayerDimension.put(player.getUUID(), player.level().dimension().identifier().toString());
                
                // Sync global waypoints
                sendGlobalWaypoints(player, new SyncGlobalWaypointsPayload(globalWaypoints));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(AddGlobalWaypointPayload.ID, (payload, context) -> {
            ServerPlayer sender = context.player();
            context.server().execute(() -> {
                boolean changed = switch (payload.action()) {
                    case ADD -> {
                        globalWaypoints.add(new SyncGlobalWaypointsPayload.GlobalWaypoint(
                            UUID.randomUUID().toString(), payload.name(), payload.x(), payload.y(), payload.z(),
                            payload.color(), payload.dimension(), payload.icon(), sender.getUUID().toString(),
                            sender.getName().getString()
                        ));
                        yield true;
                    }
                    case UPDATE -> {
                        int index = findGlobalWaypoint(payload.id());
                        if (index < 0) yield false;
                        SyncGlobalWaypointsPayload.GlobalWaypoint old = globalWaypoints.get(index);
                        globalWaypoints.set(index, new SyncGlobalWaypointsPayload.GlobalWaypoint(
                            payload.id(), payload.name(), payload.x(), payload.y(), payload.z(), payload.color(),
                            payload.dimension(), payload.icon(), old.creatorUuid(), old.creatorName()
                        ));
                        yield true;
                    }
                    case REMOVE -> globalWaypoints.removeIf(wp -> wp.id() != null && wp.id().equals(payload.id()));
                    case DELETE_OWNED -> globalWaypoints.removeIf(wp -> sender.getUUID().toString().equals(wp.creatorUuid()));
                };
                if (!changed) return;
                saveGlobalWaypoints();
                SyncGlobalWaypointsPayload syncPayload = new SyncGlobalWaypointsPayload(new ArrayList<>(globalWaypoints));
                for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
                    if (MODDED_PLAYERS.contains(player.getUUID())) {
                        sendGlobalWaypoints(player, syncPayload);
                    }
                }
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MODDED_PLAYERS.remove(handler.getPlayer().getUUID());
            lastChunkView.remove(handler.getPlayer().getUUID());
            lastPlayerDimension.remove(handler.getPlayer().getUUID());
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            clientTintedChunks.clear();
            lastChunkView.clear();
            lastPlayerDimension.clear();
            Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            storage = new MapStorage(worldDir.resolve("worldmap").resolve("global"));
            
            globalWaypointsFile = worldDir.resolve("worldmap").resolve("global_waypoints.json").toFile();
            globalWaypoints.clear();
            loadGlobalWaypoints();
        });

        ServerTickEvents.END_SERVER_TICK.register(WorldMapServer::tick);
    }

    private static int findGlobalWaypoint(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < globalWaypoints.size(); i++) {
            if (id.equals(globalWaypoints.get(i).id())) return i;
        }
        return -1;
    }

    private static void sendGlobalWaypoints(ServerPlayer player, SyncGlobalWaypointsPayload payload) {
        ServerPlayNetworking.send(player, payload);
        if (ServerPlayNetworking.canSend(player, com.runterya.worldmap.network.SyncGlobalWaypointOwnersPayload.ID)) {
            List<com.runterya.worldmap.network.SyncGlobalWaypointOwnersPayload.WaypointOwner> owners =
                payload.waypoints().stream()
                    .map(wp -> new com.runterya.worldmap.network.SyncGlobalWaypointOwnersPayload.WaypointOwner(
                        wp.id(), wp.creatorUuid(), wp.creatorName()))
                    .toList();
            ServerPlayNetworking.send(player,
                new com.runterya.worldmap.network.SyncGlobalWaypointOwnersPayload(owners));
        }
    }

    /**
     * Send already known map data for newly visible chunks. Clients report freshly
     * loaded chunks after extracting them with their vanilla client resources.
     */
    private static void enqueuePlayerView(ServerPlayer player, ChunkTrackingView oldView, ChunkTrackingView newView) {
        ChunkTrackingView.difference(oldView, newView,
            chunkPos -> {
                int cx = chunkPos.getMinBlockX() >> 4;
                int cz = chunkPos.getMinBlockZ() >> 4;
                String dimension = player.level().dimension().identifier().toString();
                java.util.List<String> mapDimensions;
                if (NetherStyleDimensions.isNetherStyle(player.level())) {
                    int centerLayerY = NetherMapView.getPlayerLayerY(player.blockPosition().getY(),
                        player.level().getMinY(), player.level().getMaxY());
                    java.util.LinkedHashSet<String> nearbyLayerDimensions = new java.util.LinkedHashSet<>();
                    nearbyLayerDimensions.add(NetherMapView.BEDROCK_SURFACE.storageDimension(dimension));
                    for (int offset = -3; offset <= 3; offset++) {
                        int layerY = NetherMapView.getNearbyPlayerLayerY(centerLayerY, offset,
                            player.level().getMinY(), player.level().getMaxY());
                        nearbyLayerDimensions.add(NetherMapView.CAVE_LAYER.storageDimension(dimension, layerY));
                    }
                    mapDimensions = java.util.List.copyOf(nearbyLayerDimensions);
                } else {
                    mapDimensions = java.util.List.of(NetherMapView.BEDROCK_SURFACE.storageDimension(dimension));
                }
                for (String mapDimension : mapDimensions) {
                    DimensionChunkKey key = new DimensionChunkKey(mapDimension, chunkKey(cx, cz));
                    int[] saved = clientTintedChunks.get(key);
                    if (saved == null && storage != null) saved = storage.getChunk(mapDimension, cx, cz);
                    if (saved != null) {
                        Set<UUID> explorers = storage == null ? Set.of() : storage.getExplorers(mapDimension, cx, cz);
                        ServerPlayNetworking.send(player, new MapUpdatePayload(mapDimension, cx, cz, saved, List.copyOf(explorers)));
                    }
                }

            },
            chunkPos -> {}
        );
    }

    private static void sendDiscoveredMaps(ServerPlayer player) {
        if (storage == null) return;
        for (MapStorage.ExploredChunk chunk : storage.getDiscoveredChunks()) {
            int[] colors = clientTintedChunks.get(new DimensionChunkKey(
                chunk.dimension(), chunkKey(chunk.chunkX(), chunk.chunkZ())
            ));
            if (colors == null) colors = storage.getChunk(chunk.dimension(), chunk.chunkX(), chunk.chunkZ());
            if (colors == null) continue;
            ServerPlayNetworking.send(player, new MapUpdatePayload(
                chunk.dimension(), chunk.chunkX(), chunk.chunkZ(), colors, List.copyOf(chunk.explorers())
            ));
        }
    }

    private static void loadGlobalWaypoints() {
        if (globalWaypointsFile != null && globalWaypointsFile.exists()) {
            try (Reader reader = new FileReader(globalWaypointsFile)) {
                Type listType = new TypeToken<ArrayList<SyncGlobalWaypointsPayload.GlobalWaypoint>>(){}.getType();
                List<SyncGlobalWaypointsPayload.GlobalWaypoint> loaded = GSON.fromJson(reader, listType);
                if (loaded != null) {
                    globalWaypoints.clear();
                    boolean migrated = false;
                    for (SyncGlobalWaypointsPayload.GlobalWaypoint wp : loaded) {
                        if (wp.id() == null || wp.id().isEmpty()) {
                            globalWaypoints.add(new SyncGlobalWaypointsPayload.GlobalWaypoint(
                                UUID.randomUUID().toString(), wp.name(), wp.x(), wp.y(), wp.z(), wp.color(),
                                wp.dimension(), wp.icon(), wp.creatorUuid(), wp.creatorName()
                            ));
                            migrated = true;
                        } else {
                            globalWaypoints.add(wp);
                        }
                    }
                    if (migrated) saveGlobalWaypoints();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void saveGlobalWaypoints() {
        if (globalWaypointsFile != null) {
            try (Writer writer = new FileWriter(globalWaypointsFile)) {
                GSON.toJson(globalWaypoints, writer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void tick(MinecraftServer server) {
        if (++tickCount >= 20) {
            tickCount = 0;
            if (MODDED_PLAYERS.isEmpty()) return;

            List<PlayerPosPayload.PlayerPos> positions = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (MODDED_PLAYERS.contains(player.getUUID())) {
                    positions.add(new PlayerPosPayload.PlayerPos(
                        player.getUUID(), player.getX(), player.getZ(), player.getYRot(), player.getName().getString(),
                        player.level().dimension().identifier().toString()
                    ));
                }
            }

            if (!positions.isEmpty()) {
                PlayerPosPayload payload = new PlayerPosPayload(positions);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!MODDED_PLAYERS.contains(player.getUUID())) continue;

                    ServerPlayNetworking.send(player, payload);

                    // Detect chunk view change and enqueue new chunks
                    ChunkTrackingView currentView = player.getChunkTrackingView();
                    ChunkTrackingView previousView = lastChunkView.getOrDefault(player.getUUID(), ChunkTrackingView.EMPTY);
                    String currentDimension = player.level().dimension().identifier().toString();
                    String previousDimension = lastPlayerDimension.get(player.getUUID());
                    if (!currentDimension.equals(previousDimension)) {
                        enqueuePlayerView(player, ChunkTrackingView.EMPTY, currentView);
                    } else if (currentView != previousView) {
                        enqueuePlayerView(player, previousView, currentView);
                    }
                    if (currentView != previousView || !currentDimension.equals(previousDimension)) {
                        lastChunkView.put(player.getUUID(), currentView);
                        lastPlayerDimension.put(player.getUUID(), currentDimension);
                    }
                }
            }
        }
    }

    public static void broadcastMapUpdate(MinecraftServer server, String dimension, int chunkX, int chunkZ, int[] colors,
                                          Set<UUID> explorers) {
        if (MODDED_PLAYERS.isEmpty()) return;
        MapUpdatePayload payload = new MapUpdatePayload(dimension, chunkX, chunkZ, colors, List.copyOf(explorers));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (MODDED_PLAYERS.contains(player.getUUID())) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
