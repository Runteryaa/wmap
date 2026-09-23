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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldMapServer {
    public static final Set<UUID> MODDED_PLAYERS = ConcurrentHashMap.newKeySet();
    public static MapStorage storage;
    private static int tickCount = 0;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File globalWaypointsFile;
    private static final List<SyncGlobalWaypointsPayload.GlobalWaypoint> globalWaypoints = new ArrayList<>();

    /** How many chunk extractions to process per server tick (avoids freeze). */
    private static final int CHUNKS_PER_TICK = 8;

    /** Background thread for disk I/O so it doesn't block the server thread. */
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "worldmap-io");
        t.setDaemon(true);
        return t;
    });

    /** Background thread pool for CPU-intensive chunk extraction. */
    private static final ExecutorService extractionExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), r -> {
        Thread t = new Thread(r, "worldmap-extractor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY); // Prevent stealing CPU from main game loop
        return t;
    });

    /** Queue of pending chunk sends: [player UUID, chunkX, chunkZ] */
    private record ChunkTask(UUID playerUUID, int cx, int cz) {}
    private static final Queue<ChunkTask> chunkQueue = new ConcurrentLinkedQueue<>();

    /** Track the previous ChunkTrackingView for each player to detect newly added chunks. */
    private static final java.util.Map<UUID, ChunkTrackingView> lastChunkView = new ConcurrentHashMap<>();

    /** Track chunks that had block updates and need extraction & broadcast. */
    private static final Set<LevelChunk> dirtyChunks = ConcurrentHashMap.newKeySet();



    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(MapColorReportPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                if (!MODDED_PLAYERS.contains(context.player().getUUID()) || payload.colors().length != 256) {
                    return;
                }
                // Client biome color resources are authoritative for map tinting;
                // persist and share the vanilla-resolved colors it reports.
                broadcastMapUpdate(context.server(), payload.chunkX(), payload.chunkZ(), payload.colors());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(com.runterya.worldmap.network.HandshakePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                MODDED_PLAYERS.add(context.player().getUUID());
                ServerPlayer player = context.player();

                // Send saved disk data immediately (fast, no extraction needed)
                if (storage != null) {
                    player.getChunkTrackingView().forEach(chunkPos -> {
                        int cx = chunkPos.getMinBlockX() >> 4;
                        int cz = chunkPos.getMinBlockZ() >> 4;
                        int[] saved = storage.getChunk(cx, cz);
                        if (saved != null) {
                            ServerPlayNetworking.send(player, new MapUpdatePayload(cx, cz, saved));
                        }
                    });
                }

                // Queue fresh extraction of all chunks in view (spread over multiple ticks)
                enqueuePlayerView(player, ChunkTrackingView.EMPTY, player.getChunkTrackingView());
                lastChunkView.put(player.getUUID(), player.getChunkTrackingView());
                
                // Sync global waypoints
                ServerPlayNetworking.send(player, new SyncGlobalWaypointsPayload(globalWaypoints));
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(AddGlobalWaypointPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                SyncGlobalWaypointsPayload.GlobalWaypoint wp = new SyncGlobalWaypointsPayload.GlobalWaypoint(
                    payload.name(), payload.x(), payload.y(), payload.z(), payload.color(), payload.dimension()
                );
                globalWaypoints.add(wp);
                saveGlobalWaypoints();
                
                SyncGlobalWaypointsPayload syncPayload = new SyncGlobalWaypointsPayload(List.of(wp)); // sending just the new one
                for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
                    if (MODDED_PLAYERS.contains(player.getUUID())) {
                        ServerPlayNetworking.send(player, syncPayload);
                    }
                }
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MODDED_PLAYERS.remove(handler.getPlayer().getUUID());
            lastChunkView.remove(handler.getPlayer().getUUID());
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            storage = new MapStorage(worldDir.resolve("worldmap").resolve("global"));
            
            globalWaypointsFile = worldDir.resolve("worldmap").resolve("global_waypoints.json").toFile();
            loadGlobalWaypoints();
        });

        ServerTickEvents.END_SERVER_TICK.register(WorldMapServer::tick);
    }

    /**
     * Enqueue newly visible chunks (difference between old and new view) for processing.
     */
    private static void enqueuePlayerView(ServerPlayer player, ChunkTrackingView oldView, ChunkTrackingView newView) {
        ChunkTrackingView.difference(oldView, newView,
            chunkPos -> {
                int cx = chunkPos.getMinBlockX() >> 4;
                int cz = chunkPos.getMinBlockZ() >> 4;
                chunkQueue.offer(new ChunkTask(player.getUUID(), cx, cz));
            },
            chunkPos -> {}
        );
    }

    private static void loadGlobalWaypoints() {
        if (globalWaypointsFile != null && globalWaypointsFile.exists()) {
            try (Reader reader = new FileReader(globalWaypointsFile)) {
                Type listType = new TypeToken<ArrayList<SyncGlobalWaypointsPayload.GlobalWaypoint>>(){}.getType();
                List<SyncGlobalWaypointsPayload.GlobalWaypoint> loaded = GSON.fromJson(reader, listType);
                if (loaded != null) {
                    globalWaypoints.clear();
                    globalWaypoints.addAll(loaded);
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

    /**
     * Extract and send a single chunk's map data, saving to disk async.
     */
    private static void processChunkTask(ChunkTask task, MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(task.playerUUID());
        if (player == null || !MODDED_PLAYERS.contains(task.playerUUID())) return;

        ServerLevel level = (ServerLevel) player.level();
        LevelChunk chunk = level.getChunkSource().getChunkNow(task.cx(), task.cz());
        if (chunk == null) return;

        extractionExecutor.submit(() -> {
            int[] colors = MapColorExtractor.extract(chunk);
            
            server.execute(() -> {
                if (server.getPlayerList().getPlayer(task.playerUUID()) != null) {
                    ServerPlayNetworking.send(player, new MapUpdatePayload(task.cx(), task.cz(), colors));
                }
            });

            // Save to disk asynchronously
            if (storage != null) {
                ioExecutor.submit(() -> storage.updateChunk(task.cx(), task.cz(), colors));
            }
        });
    }

    private static void tick(MinecraftServer server) {
        // Process a batch of queued chunk tasks per tick
        int processed = 0;
        ChunkTask task;
        while (processed < CHUNKS_PER_TICK && (task = chunkQueue.poll()) != null) {
            processChunkTask(task, server);
            processed++;
        }

        if (++tickCount >= 20) {
            // Process dirty chunks caused by block updates
            int dirtyProcessed = 0;
            java.util.Iterator<LevelChunk> iterator = dirtyChunks.iterator();
            while (iterator.hasNext() && dirtyProcessed < CHUNKS_PER_TICK) {
                LevelChunk dirtyChunk = iterator.next();
                iterator.remove();
                
                int cx = dirtyChunk.getPos().getMinBlockX() >> 4;
                int cz = dirtyChunk.getPos().getMinBlockZ() >> 4;
                
                extractionExecutor.submit(() -> {
                    int[] colors = MapColorExtractor.extract(dirtyChunk);
                    server.execute(() -> {
                        broadcastMapUpdate(server, cx, cz, colors);
                    });
                });
                
                dirtyProcessed++;
            }
            
            tickCount = 0;
            if (MODDED_PLAYERS.isEmpty()) return;

            List<PlayerPosPayload.PlayerPos> positions = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (MODDED_PLAYERS.contains(player.getUUID())) {
                    positions.add(new PlayerPosPayload.PlayerPos(
                        player.getUUID(), player.getX(), player.getZ(), player.getName().getString()
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
                    if (currentView != previousView) {
                        enqueuePlayerView(player, previousView, currentView);
                        lastChunkView.put(player.getUUID(), currentView);
                    }
                }
            }
        }
    }

    public static void markChunkDirty(LevelChunk chunk) {
        dirtyChunks.add(chunk);
    }

    public static void broadcastMapUpdate(MinecraftServer server, int chunkX, int chunkZ, int[] colors) {
        if (MODDED_PLAYERS.isEmpty()) return;
        if (storage != null) {
            ioExecutor.submit(() -> storage.updateChunk(chunkX, chunkZ, colors));
        }
        MapUpdatePayload payload = new MapUpdatePayload(chunkX, chunkZ, colors);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (MODDED_PLAYERS.contains(player.getUUID())) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
