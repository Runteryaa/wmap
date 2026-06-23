package com.runterya.worldmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import com.runterya.worldmap.network.HandshakePayload;
import com.runterya.worldmap.network.MapUpdatePayload;
import com.runterya.worldmap.network.PlayerPosPayload;
import com.runterya.worldmap.client.ClientMapManager;
import com.runterya.worldmap.client.ClientMapStorage;
import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import net.minecraft.resources.Identifier;
import java.util.Random;

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

        mapKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.worldmap.open", 
            InputConstants.Type.KEYSYM, 
            GLFW.GLFW_KEY_M, 
            CATEGORY
        ));

        waypointKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.worldmap.add_waypoint", 
            InputConstants.Type.KEYSYM, 
            GLFW.GLFW_KEY_B, 
            CATEGORY
        ));

        WaypointManager.load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mapKeyBinding != null) {
                while (mapKeyBinding.consumeClick()) {
                    if (client.player != null) {
                        client.setScreen(new com.runterya.worldmap.gui.WorldMapScreen());
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
                        client.setScreen(new com.runterya.worldmap.gui.WaypointAddScreen(client.screen, x, y, z, dim));
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

        // Waypoint Particle Beam
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != null && client.player != null) {
                String currentDim = client.level.dimension().identifier().toString();
                for (Waypoint wp : WaypointManager.getWaypoints()) {
                    if (wp.getDimension().equals(currentDim)) {
                        double distSq = client.player.distanceToSqr(wp.getX(), client.player.getY(), wp.getZ());
                        if (distSq < 16384) { // Render beam if within ~128 blocks
                            int color = wp.getColor() | 0xFF000000;
                            net.minecraft.core.particles.DustParticleOptions options = new net.minecraft.core.particles.DustParticleOptions(color, 2.0f);
                            
                            // Spawn a solid vertical beam around the player's Y level
                            double startY = Math.max(client.level.getMinY(), client.player.getY() - 64);
                            double endY = Math.min(client.level.getMaxY(), client.player.getY() + 64);
                            
                            for (double y = startY; y <= endY; y += 4.0) {
                                client.level.addParticle(
                                    options,
                                    wp.getX() + 0.5,
                                    y + (client.player.tickCount % 20) / 5.0,
                                    wp.getZ() + 0.5,
                                    0, 0.05, 0
                                );
                            }
                        }
                    }
                }
            }
        });

        // 3D Waypoint Text
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            
            String currentDim = client.level.dimension().identifier().toString();
            java.util.Set<Integer> activeWpIds = new java.util.HashSet<>();
            
            for (Waypoint wp : WaypointManager.getWaypoints()) {
                if (!wp.getDimension().equals(currentDim)) continue;
                
                // Only render text if within 256 blocks (same as particles)
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
                    net.minecraft.world.entity.Display.TextDisplay newDisplay = new net.minecraft.world.entity.Display.TextDisplay(net.minecraft.world.entity.EntityType.TEXT_DISPLAY, client.level);
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
            wasDead = false;
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(com.runterya.worldmap.network.SyncGlobalWaypointsPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                for (com.runterya.worldmap.network.SyncGlobalWaypointsPayload.GlobalWaypoint wp : payload.waypoints()) {
                    WaypointManager.addGlobalWaypoint(new Waypoint(wp.name(), wp.x(), wp.y(), wp.z(), wp.color(), wp.dimension(), true));
                }
            });
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(MapUpdatePayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                ClientMapManager.receiveUpdate(payload.chunkX(), payload.chunkZ(), payload.colors());
                ClientMapStorage.saveChunk(payload.chunkX(), payload.chunkZ(), payload.colors());
            });
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(PlayerPosPayload.ID, (payload, ctx) -> {
            ctx.client().execute(() -> {
                ClientMapManager.updatePlayerPositions(payload.positions());
            });
        });
    }
}

