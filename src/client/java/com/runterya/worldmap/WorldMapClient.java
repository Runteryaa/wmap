package com.runterya.worldmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

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

        mapKeyBinding = ClientPlatform.createKeyMapping("key.worldmap.open", true, CATEGORY);

        waypointKeyBinding = ClientPlatform.createKeyMapping("key.worldmap.add_waypoint", false, CATEGORY);

        WaypointManager.load();
        WaypointBeaconBeamRenderer.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientMapManager.processPendingChunks(2);

            if (mapKeyBinding != null) {
                while (mapKeyBinding.consumeClick()) {
                    if (client.player != null) {
                        ClientPlatform.setScreen(client, new com.runterya.worldmap.gui.WorldMapScreen());
                    }
                }
            }
            if (waypointKeyBinding != null) {
                while (waypointKeyBinding.consumeClick()) {
                    if (client.player != null && client.level != null) {
                        int x = client.player.getBlockX();
                        int y = client.player.getBlockY();
                        int z = client.player.getBlockZ();
                        String dim = client.level.dimension().identifier().toString();
                        ClientPlatform.setScreen(client, new com.runterya.worldmap.gui.WaypointAddScreen(null, x, y, z, dim));
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

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ClientMapManager.queueChunk(chunk));

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
                ClientMapStorage.loadAllIntoManager();
            });
            if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(HandshakePayload.ID)) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new HandshakePayload());
            }
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, cl) -> {
            ClientMapManager.clear();
            ClientMapStorage.clearCurrentServer();
            WaypointManager.clearGlobalWaypoints();
            WaypointManager.setServerWaypointSharingAvailable(false);
            wasDead = false;
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.runterya.worldmap.network.SyncGlobalWaypointsPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                java.util.List<Waypoint> syncedWaypoints = new java.util.ArrayList<>();
                for (com.runterya.worldmap.network.SyncGlobalWaypointsPayload.GlobalWaypoint wp : payload.waypoints()) {
                    Waypoint waypoint = new Waypoint(wp.name(), wp.x(), wp.y(), wp.z(), wp.color(), wp.dimension(), true);
                    waypoint.setGlobalId(wp.id());
                    syncedWaypoints.add(waypoint);
                }
                WaypointManager.replaceGlobalWaypoints(syncedWaypoints);
            });
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(MapUpdatePayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (ClientMapManager.receiveServerUpdate(payload.chunkX(), payload.chunkZ(), payload.colors())) {
                    ClientMapStorage.saveChunk(payload.chunkX(), payload.chunkZ(), payload.colors());
                }
            });
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(PlayerPosPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                ClientMapManager.updatePlayerPositions(payload.positions());
            });
        });
    }
}
