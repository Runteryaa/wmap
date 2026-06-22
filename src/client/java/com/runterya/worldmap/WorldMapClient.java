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

        });

        // Waypoint Particle Beam (Vanilla aesthetic)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != null && client.player != null) {
                String currentDim = client.level.dimension().identifier().toString();
                for (Waypoint wp : WaypointManager.getWaypoints()) {
                    if (wp.getDimension().equals(currentDim)) {
                        double distSq = client.player.distanceToSqr(wp.getX(), client.player.getY(), wp.getZ());
                        if (distSq < 16384) { // Render beam if within ~128 blocks
                            int color = wp.getColor() | 0xFF000000;
                            net.minecraft.core.particles.DustParticleOptions options = new net.minecraft.core.particles.DustParticleOptions(color, 2.0f);
                            
                            // Spawn a solid vertical beam (optimized particle count)
                            for (int i = 0; i < 10; i++) {
                                client.level.addParticle(
                                    options,
                                    wp.getX() + 0.5,
                                    wp.getY() + (i * 4) + (client.player.tickCount % 20) / 5.0,
                                    wp.getZ() + 0.5,
                                    0, 0.05, 0
                                );
                            }
                        }
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

