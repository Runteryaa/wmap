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
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private static final java.util.Map<UUID, MapSyncTransfer> mapTransfers = new ConcurrentHashMap<>();

    private record SyncChunk(String dimension, int chunkX, int chunkZ) {}

    /** Metadata queue stays small; chunk colors are loaded in small asynchronous batches. */
    private static final class MapSyncTransfer {
        private final ArrayDeque<SyncChunk> pending = new ArrayDeque<>();
        private final Set<DimensionChunkKey> queued = new HashSet<>();
        private final Set<DimensionChunkKey> inFlight = new HashSet<>();
        private final Map<DimensionChunkKey, MapUpdatePayload> updatesWhileLoading = new java.util.HashMap<>();
        private final ArrayDeque<MapUpdatePayload> ready = new ArrayDeque<>();
        private boolean loading;

        synchronized void addFirst(List<SyncChunk> chunks) {
            for (int i = chunks.size() - 1; i >= 0; i--) add(chunks.get(i), true);
        }

        synchronized void addLast(List<SyncChunk> chunks) {
            for (SyncChunk chunk : chunks) add(chunk, false);
        }

        private void add(SyncChunk chunk, boolean first) {
            DimensionChunkKey key = new DimensionChunkKey(chunk.dimension(), chunkKey(chunk.chunkX(), chunk.chunkZ()));
            if (!queued.add(key)) return;
            if (first) pending.addFirst(chunk);
            else pending.addLast(chunk);
        }

        synchronized List<SyncChunk> takeBatch(int maxCount) {
            if (loading || pending.isEmpty() || ready.size() >= 16) return List.of();
            loading = true;
            List<SyncChunk> batch = new ArrayList<>(Math.min(maxCount, pending.size()));
            while (batch.size() < maxCount && !pending.isEmpty()) {
                SyncChunk chunk = pending.removeFirst();
                batch.add(chunk);
                inFlight.add(new DimensionChunkKey(chunk.dimension(), chunkKey(chunk.chunkX(), chunk.chunkZ())));
            }
            return batch;
        }

        synchronized void completeBatch(List<SyncChunk> requested, List<MapUpdatePayload> payloads) {
            Map<DimensionChunkKey, MapUpdatePayload> loaded = new java.util.HashMap<>();
            for (MapUpdatePayload payload : payloads) {
                DimensionChunkKey key = new DimensionChunkKey(payload.dimension(), chunkKey(payload.chunkX(), payload.chunkZ()));
                loaded.put(key, payload);
            }
            for (SyncChunk chunk : requested) {
                DimensionChunkKey key = new DimensionChunkKey(chunk.dimension(), chunkKey(chunk.chunkX(), chunk.chunkZ()));
                inFlight.remove(key);
                MapUpdatePayload newest = updatesWhileLoading.remove(key);
                MapUpdatePayload result = newest != null ? newest : loaded.get(key);
                if (result != null) ready.addLast(result);
            }
            loading = false;
        }

        synchronized boolean promoteUpdate(MapUpdatePayload payload) {
            DimensionChunkKey key = new DimensionChunkKey(payload.dimension(), chunkKey(payload.chunkX(), payload.chunkZ()));
            if (!queued.contains(key)) return false;
            boolean removed = pending.removeIf(chunk -> chunk.dimension().equals(key.dimension())
                && chunkKey(chunk.chunkX(), chunk.chunkZ()) == key.chunkKey());
            if (removed) {
                ready.addFirst(payload);
                return true;
            }
            boolean replaced = false;
            for (var iterator = ready.iterator(); iterator.hasNext();) {
                MapUpdatePayload queuedPayload = iterator.next();
                if (queuedPayload.dimension().equals(key.dimension())
                    && chunkKey(queuedPayload.chunkX(), queuedPayload.chunkZ()) == key.chunkKey()) {
                    iterator.remove();
                    replaced = true;
                    break;
                }
            }
            if (replaced) {
                ready.addFirst(payload);
                return true;
            }
            if (inFlight.contains(key)) {
                updatesWhileLoading.put(key, payload);
                return true;
            }
            return false;
        }

        synchronized List<MapUpdatePayload> drain(int maxCount) {
            List<MapUpdatePayload> batch = new ArrayList<>(Math.min(maxCount, ready.size()));
            while (batch.size() < maxCount && !ready.isEmpty()) batch.add(ready.removeFirst());
            return batch;
        }
    }

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
                    if (!LayeredDimensions.contains(context.player().level())
                        || layerY < minY || layerY >= maxY
                        || layerY > LayeredDimensions.getMaxLayerY(playerDimension, minY, maxY)
                        || Math.floorMod(layerY, NetherMapView.CAVE_LAYER_STEP) != 0) {
                        return;
                    }
                }
                MinecraftServer server = context.server();
                UUID playerId = context.player().getUUID();
                MapStorage activeStorage = storage;
                DimensionChunkKey key = new DimensionChunkKey(payload.dimension(),
                    chunkKey(payload.chunkX(), payload.chunkZ()));
                ioExecutor.submit(() -> {
                    int[] oldColors = activeStorage == null ? clientTintedChunks.get(key)
                        : activeStorage.getChunk(payload.dimension(), payload.chunkX(), payload.chunkZ());
                    boolean colorsChanged = oldColors == null || !Arrays.equals(oldColors, payload.colors());
                    Set<UUID> oldExplorers = activeStorage == null ? Set.of()
                        : activeStorage.getExplorers(payload.dimension(), payload.chunkX(), payload.chunkZ());
                    Set<UUID> explorers = activeStorage == null ? Set.of(playerId)
                        : activeStorage.addExplorer(payload.dimension(), payload.chunkX(), payload.chunkZ(), playerId);
                    boolean explorersChanged = !oldExplorers.contains(playerId);
                    if (colorsChanged && activeStorage != null) {
                        activeStorage.updateChunk(payload.dimension(), payload.chunkX(), payload.chunkZ(), payload.colors());
                    }
                    final int[] effectiveColors = oldColors == null || colorsChanged
                        ? payload.colors().clone() : oldColors;
                    server.execute(() -> {
                        if (activeStorage != storage) return;
                        clientTintedChunks.put(key, effectiveColors);
                        if (colorsChanged || explorersChanged) {
                            broadcastMapUpdate(server, payload.dimension(), payload.chunkX(), payload.chunkZ(),
                                effectiveColors, explorers);
                        }
                    });
                });
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(com.runterya.worldmap.network.HandshakePayload.ID, (payload, context) -> {
            MinecraftServer server = context.server();
            server.execute(() -> {
                MODDED_PLAYERS.add(context.player().getUUID());
                ServerPlayer player = context.player();

                // Restore all discovered shared chunks first, then fill any older
                // map-only chunks that have no ownership record from the current view.
                enqueuePlayerView(player, ChunkTrackingView.EMPTY, player.getChunkTrackingView());
                sendDiscoveredMaps(server, player);
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
                            payload.color(), payload.dimension(), payload.icon(), payload.category(), payload.note(),
                            sender.getUUID().toString(), sender.getName().getString()
                        ));
                        yield true;
                    }
                    case UPDATE -> {
                        int index = findGlobalWaypoint(payload.id());
                        if (index < 0) yield false;
                        SyncGlobalWaypointsPayload.GlobalWaypoint old = globalWaypoints.get(index);
                        globalWaypoints.set(index, new SyncGlobalWaypointsPayload.GlobalWaypoint(
                            payload.id(), payload.name(), payload.x(), payload.y(), payload.z(), payload.color(),
                            payload.dimension(), payload.icon(), payload.category(), payload.note(),
                            old.creatorUuid(), old.creatorName()
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
            mapTransfers.remove(handler.getPlayer().getUUID());
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
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MapStorage activeStorage = storage;
            if (activeStorage != null) {
                try {
                    ioExecutor.submit(() -> {
                        for (int batch = 0; batch < 32 && activeStorage.hasPendingWrites(); batch++) {
                            activeStorage.flushPending();
                        }
                    }).get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    System.err.println("Timed out flushing world map data during server shutdown");
                    exception.printStackTrace();
                }
            }
        });
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
        MapSyncTransfer transfer = mapTransfers.computeIfAbsent(player.getUUID(), ignored -> new MapSyncTransfer());
        List<SyncChunk> nearby = new ArrayList<>();
        ChunkTrackingView.difference(oldView, newView,
            chunkPos -> {
                int cx = chunkPos.getMinBlockX() >> 4;
                int cz = chunkPos.getMinBlockZ() >> 4;
                String dimension = player.level().dimension().identifier().toString();
                java.util.List<String> mapDimensions;
                if (LayeredDimensions.contains(player.level())) {
                    int centerLayerY = NetherMapView.getPlayerLayerY(dimension, player.blockPosition().getY(),
                        player.level().getMinY(), player.level().getMaxY());
                    java.util.LinkedHashSet<String> nearbyLayerDimensions = new java.util.LinkedHashSet<>();
                    nearbyLayerDimensions.add(NetherMapView.BEDROCK_SURFACE.storageDimension(dimension));
                    for (int offset : new int[] {0, -1, 1, -2, 2, -3, 3}) {
                        int layerY = NetherMapView.getNearbyPlayerLayerY(dimension, centerLayerY, offset,
                            player.level().getMinY(), player.level().getMaxY());
                        nearbyLayerDimensions.add(NetherMapView.CAVE_LAYER.storageDimension(dimension, layerY));
                    }
                    mapDimensions = java.util.List.copyOf(nearbyLayerDimensions);
                } else {
                    mapDimensions = java.util.List.of(NetherMapView.BEDROCK_SURFACE.storageDimension(dimension));
                }
                for (String mapDimension : mapDimensions) {
                    nearby.add(new SyncChunk(mapDimension, cx, cz));
                }

            },
            chunkPos -> {}
        );
        nearby.sort(Comparator.<SyncChunk>comparingInt(chunk -> mapDimensionPriority(chunk.dimension(),
                player.level().dimension().identifier().toString(),
                NetherMapView.getPlayerLayerY(player.level().dimension().identifier().toString(),
                    player.blockPosition().getY(), player.level().getMinY(), player.level().getMaxY())))
            .thenComparingLong(chunk -> squaredDistance(chunk.chunkX(), chunk.chunkZ(),
                player.chunkPosition().x(), player.chunkPosition().z())));
        transfer.addFirst(nearby);
    }

    private static int mapDimensionPriority(String mapDimension, String currentDimension, int playerLayerY) {
        if (!currentDimension.equals(NetherMapView.gameDimension(mapDimension))) return 1;
        int layerY = NetherMapView.getCaveLayerY(mapDimension);
        if (layerY == playerLayerY) return 0;
        if (layerY == Integer.MIN_VALUE) return 1;
        return 2 + Math.abs(layerY - playerLayerY) / NetherMapView.CAVE_LAYER_STEP;
    }

    private static long squaredDistance(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ) {
        long dx = (long) chunkX - playerChunkX;
        long dz = (long) chunkZ - playerChunkZ;
        return dx * dx + dz * dz;
    }

    private static void sendDiscoveredMaps(MinecraftServer server, ServerPlayer player) {
        MapStorage activeStorage = storage;
        if (activeStorage == null) return;
        MapSyncTransfer transfer = mapTransfers.computeIfAbsent(player.getUUID(), ignored -> new MapSyncTransfer());
        String dimension = player.level().dimension().identifier().toString();
        int playerChunkX = player.chunkPosition().x();
        int playerChunkZ = player.chunkPosition().z();
        int playerLayerY = LayeredDimensions.contains(player.level())
            ? NetherMapView.getPlayerLayerY(dimension, player.blockPosition().getY(),
                player.level().getMinY(), player.level().getMaxY())
            : Integer.MIN_VALUE;
        ioExecutor.submit(() -> {
            List<SyncChunk> discovered = activeStorage.getDiscoveredChunks().stream()
                .map(chunk -> new SyncChunk(chunk.dimension(), chunk.chunkX(), chunk.chunkZ()))
                .sorted(Comparator.comparingInt((SyncChunk chunk) -> mapDimensionPriority(
                        chunk.dimension(), dimension, playerLayerY))
                    .thenComparingLong(chunk -> squaredDistance(chunk.chunkX(), chunk.chunkZ(), playerChunkX, playerChunkZ)))
                .toList();
            server.execute(() -> {
                if (storage == activeStorage && MODDED_PLAYERS.contains(player.getUUID())) {
                    transfer.addLast(discovered);
                }
            });
        });
    }

    private static void processMapTransfers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!MODDED_PLAYERS.contains(player.getUUID())) continue;
            MapSyncTransfer transfer = mapTransfers.get(player.getUUID());
            if (transfer == null) continue;
            for (MapUpdatePayload payload : transfer.drain(8)) {
                ServerPlayNetworking.send(player, payload);
            }
            List<SyncChunk> batch = transfer.takeBatch(16);
            if (batch.isEmpty()) continue;
            MapStorage activeStorage = storage;
            ioExecutor.submit(() -> {
                List<MapUpdatePayload> payloads = new ArrayList<>(batch.size());
                for (SyncChunk chunk : batch) {
                    DimensionChunkKey key = new DimensionChunkKey(chunk.dimension(), chunkKey(chunk.chunkX(), chunk.chunkZ()));
                    int[] colors = clientTintedChunks.get(key);
                    if (colors == null && activeStorage != null) {
                        colors = activeStorage.getChunk(chunk.dimension(), chunk.chunkX(), chunk.chunkZ());
                    }
                    if (colors == null) continue;
                    Set<UUID> explorers = activeStorage == null ? Set.of()
                        : activeStorage.getExplorers(chunk.dimension(), chunk.chunkX(), chunk.chunkZ());
                    payloads.add(new MapUpdatePayload(chunk.dimension(), chunk.chunkX(), chunk.chunkZ(),
                        colors.clone(), List.copyOf(explorers)));
                }
                server.execute(() -> {
                    if (storage == activeStorage && MODDED_PLAYERS.contains(player.getUUID())) {
                        transfer.completeBatch(batch, payloads);
                    } else {
                        transfer.completeBatch(batch, List.of());
                    }
                });
            });
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
                        String id = wp.id();
                        if (id == null || id.isEmpty()) {
                            id = UUID.randomUUID().toString();
                            migrated = true;
                        }
                        if (wp.category() == null || wp.note() == null) migrated = true;
                        globalWaypoints.add(new SyncGlobalWaypointsPayload.GlobalWaypoint(
                            id, wp.name(), wp.x(), wp.y(), wp.z(), wp.color(), wp.dimension(), wp.icon(),
                            wp.category(), wp.note(), wp.creatorUuid(), wp.creatorName()
                        ));
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
        processMapTransfers(server);
        if (++tickCount >= 20) {
            tickCount = 0;
            MapStorage activeStorage = storage;
            if (activeStorage != null) ioExecutor.submit(activeStorage::flushPending);
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
                MapSyncTransfer transfer = mapTransfers.get(player.getUUID());
                if (transfer == null || !transfer.promoteUpdate(payload)) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        }
    }
}
