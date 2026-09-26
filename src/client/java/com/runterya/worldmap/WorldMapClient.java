package com.runterya.worldmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.runterya.worldmap.network.HandshakePayload;
import com.runterya.worldmap.network.MapUpdatePayload;
import com.runterya.worldmap.network.PlayerPosPayload;
import com.runterya.worldmap.client.ClientMapManager;
import com.runterya.worldmap.client.ClientMapStorage;
import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import com.runterya.worldmap.client.waypoint.WaypointBeaconBeamRenderer;
import com.runterya.worldmap.client.ClientPlatform;
import net.minecraft.resources.Identifier;

public class WorldMapClient implements ClientModInitializer {
    private static KeyMapping mapKeyBinding;
    private static KeyMapping waypointKeyBinding;
    private static boolean wasDead = false;

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath(WorldMapMod.MOD_ID, "category_keys")
    );

    @Override
    public void onInitializeClient() {
        WorldMapMod.LOGGER.info("WorldMap Client initializing...");

        WorldMapConfig.load();

        mapKeyBinding = ClientPlatform.createKeyMapping("key.worldmap.open", true, CATEGORY);

        waypointKeyBinding = ClientPlatform.createKeyMapping("key.worldmap.add_waypoint", false, CATEGORY);

        WaypointManager.load();
        WaypointBeaconBeamRenderer.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientMapManager.processPendingChunks(2);

            if (mapKeyBinding != null) {
                while (mapKeyBinding.consumeClick()) {
                    if (client.player != null) {
                        if (ClientPlatform.isScreen(client, com.runterya.worldmap.gui.WorldMapScreen.class)) {
                            ClientPlatform.setScreen(client, null);
                        } else {
                            ClientPlatform.setScreen(client, new com.runterya.worldmap.gui.WorldMapScreen());
                        }
                    }
                }
            }
            if (waypointKeyBinding != null) {
                while (waypointKeyBinding.consumeClick()) {
                    if (client.player != null && client.level != null) {
                        Waypoint lookedAtWaypoint = WorldMapConfig.openWaypointActionsOnLook()
                            ? findLookedAtWaypoint(client)
                            : null;
                        if (lookedAtWaypoint != null) {
                            ClientPlatform.setScreen(client, new com.runterya.worldmap.gui.WaypointContextMenuScreen(null, lookedAtWaypoint));
                        } else {
                            int x = client.player.getBlockX();
                            int y = client.player.getBlockY();
                            int z = client.player.getBlockZ();
                            String dim = client.level.dimension().identifier().toString();
                            ClientPlatform.setScreen(client, new com.runterya.worldmap.gui.WaypointAddScreen(null, x, y, z, dim));
                        }
                    }
                }
            }

            // Death Waypoint Logic
            if (client.player != null && client.level != null) {
                boolean isDead = client.player.isDeadOrDying();
                if (isDead && !wasDead) {
                    // Player just died
                    int x = client.player.getBlockX();
                    int y = client.player.getBlockY();
                    int z = client.player.getBlockZ();
                    String dim = client.level.dimension().identifier().toString();
                    
                    Waypoint deathWp = new Waypoint("Death Point", x, y, z, 0xFFFF0000, dim, false);
                    WaypointManager.addWaypoint(deathWp);
                    client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Death Waypoint added at X:" + x + " Y:" + y + " Z:" + z).withStyle(net.minecraft.ChatFormatting.RED));
                }
                wasDead = isDead;
            }
        });

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ClientMapManager.onChunkLoad(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> ClientMapManager.unloadChunk(chunk));

        // 3D Waypoint Text
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            
            String currentDim = client.level.dimension().identifier().toString();
            java.util.Set<Integer> activeWpIds = new java.util.HashSet<>();
            
            for (Waypoint wp : WaypointManager.getWaypoints()) {
                if (!wp.getDimension().equals(currentDim)) continue;
                
                // Keep waypoint labels readable without filling the entire view.
                double distSq = client.player.distanceToSqr(wp.getX() + 0.5, client.player.getY(), wp.getZ() + 0.5);
                if (distSq > 256 * 256) continue;
                
                int wpId = -1000 - Math.abs(java.util.Objects.hash(wp.getX(), wp.getY(), wp.getZ()) % 100000000);
                activeWpIds.add(wpId);
                
                double yPos = client.player.getY();
                
                net.minecraft.world.entity.Entity entity = client.level.getEntity(wpId);
                if (entity instanceof net.minecraft.world.entity.Display.TextDisplay textDisplay) {
                    textDisplay.setPos(wp.getX() + 0.5, yPos + 1.0, wp.getZ() + 0.5);
                    textDisplay.setCustomName(net.minecraft.network.chat.Component.literal(wp.getName()).withStyle(net.minecraft.ChatFormatting.GOLD));
                } else {
                    net.minecraft.world.entity.EntityType<?> textDisplayType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.fromNamespaceAndPath("minecraft", "text_display")
                    );
                    net.minecraft.world.entity.Display.TextDisplay newDisplay = new net.minecraft.world.entity.Display.TextDisplay(textDisplayType, client.level);
                    newDisplay.setId(wpId);
                    newDisplay.setPos(wp.getX() + 0.5, yPos + 1.0, wp.getZ() + 0.5);
                    newDisplay.setCustomName(net.minecraft.network.chat.Component.literal(wp.getName()).withStyle(net.minecraft.ChatFormatting.GOLD));
                    newDisplay.setCustomNameVisible(true);
                    
                    // Required for TextDisplay specifically:
                    newDisplay.setBillboardConstraints(net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
                    
                    client.level.addEntity(newDisplay);
                }
            }
            
            // Clean up deleted or out-of-range waypoints
            for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.Display.TextDisplay && entity.getId() <= -1000) {
                    if (!activeWpIds.contains(entity.getId())) {
                        entity.discard();
                    }
                }
            }
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, cl) -> {
            ClientMapStorage.setCurrentServer();
            WaypointManager.bindLegacyWaypointsToCurrentWorld();
            WaypointManager.clearGlobalWaypoints();
            WaypointManager.setServerWaypointSharingAvailable(
                ClientPlayNetworking.canSend(com.runterya.worldmap.network.AddGlobalWaypointPayload.ID)
            );
            cl.execute(() -> {
                ClientMapManager.clear();
                ClientMapManager.queueLoadedChunks();
                ClientMapStorage.loadAllIntoManager();
            });
            if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(HandshakePayload.ID)) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new HandshakePayload());
            }
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, cl) -> {
            ClientMapManager.clear();
            ClientMapManager.forgetLoadedChunks();
            ClientMapStorage.clearCurrentServer();
            WaypointManager.clearGlobalWaypoints();
            WaypointManager.setServerWaypointSharingAvailable(false);
            wasDead = false;
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.runterya.worldmap.network.SyncGlobalWaypointsPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                java.util.List<Waypoint> syncedWaypoints = new java.util.ArrayList<>();
                for (com.runterya.worldmap.network.SyncGlobalWaypointsPayload.GlobalWaypoint wp : payload.waypoints()) {
                    Waypoint waypoint = new Waypoint(wp.name(), wp.x(), wp.y(), wp.z(), wp.color(), wp.dimension(),
                        true, "", wp.icon());
                    waypoint.setGlobalId(wp.id());
                    waypoint.setCreatorUuid(wp.creatorUuid());
                    waypoint.setCreatorName(wp.creatorName());
                    syncedWaypoints.add(waypoint);
                }
                WaypointManager.replaceGlobalWaypoints(syncedWaypoints);
            });
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            com.runterya.worldmap.network.SyncGlobalWaypointOwnersPayload.ID, (payload, ctx) ->
                ctx.client().execute(() -> WaypointManager.applyGlobalWaypointOwners(payload.owners()))
        );

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(MapUpdatePayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                ClientMapManager.UpdateResult result = ClientMapManager.receiveServerUpdate(
                    payload.dimension(), payload.chunkX(), payload.chunkZ(), payload.colors(),
                    new java.util.HashSet<>(payload.explorers())
                );
                int[] effectiveColors = result.serverColorsApplied() ? payload.colors()
                    : ClientMapManager.getChunkColors(payload.dimension(), payload.chunkX(), payload.chunkZ());
                if (effectiveColors == null) effectiveColors = payload.colors();
                ClientMapStorage.saveChunk(payload.dimension(), payload.chunkX(), payload.chunkZ(), effectiveColors,
                    ClientMapManager.getExplorers(payload.dimension(), payload.chunkX(), payload.chunkZ()),
                    result.colorsChanged(), result.explorersChanged());
            });
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(PlayerPosPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                ClientMapManager.updatePlayerPositions(payload.positions());
            });
        });
    }

    private static Waypoint findLookedAtWaypoint(net.minecraft.client.Minecraft client) {
        if (client.player == null || client.level == null) return null;

        final double maxDistance = 512.0;
        Vec3 start = client.player.getEyePosition();
        Vec3 end = start.add(client.player.getViewVector(1.0F).scale(maxDistance));

        var blockHit = client.level.clip(new ClipContext(
            start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player
        ));
        double nearestDistanceSquared = blockHit.getType() == HitResult.Type.BLOCK
            ? start.distanceToSqr(blockHit.getLocation())
            : maxDistance * maxDistance;

        String dimension = client.level.dimension().identifier().toString();
        Waypoint nearest = null;
        for (Waypoint waypoint : WaypointManager.getWaypoints()) {
            if (!waypoint.getDimension().equals(dimension)) continue;

            AABB beamBounds = new AABB(
                waypoint.getX(), -128.0, waypoint.getZ(),
                waypoint.getX() + 1.0, client.level.getMaxY(), waypoint.getZ() + 1.0
            );
            var intersection = beamBounds.clip(start, end);
            if (intersection.isEmpty()) continue;

            double distanceSquared = start.distanceToSqr(intersection.get());
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = waypoint;
            }
        }
        return nearest;
    }
}
