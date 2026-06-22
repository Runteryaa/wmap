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

            // Waypoint Particle Beam (Vanilla aesthetic)
            if (client.level != null && client.player != null && client.player.tickCount % 2 == 0) {
                String currentDim = client.level.dimension().identifier().toString();
                for (Waypoint wp : WaypointManager.getWaypoints()) {
                    if (wp.getDimension().equals(currentDim)) {
                        double distSq = client.player.distanceToSqr(wp.getX(), client.player.getY(), wp.getZ());
                        if (distSq < 16384) { // Render beam if within ~128 blocks
                            // Spawn a glowing vertical beam
                            for (int i = 0; i < 20; i++) {
                                client.level.addParticle(
                                    net.minecraft.core.particles.ParticleTypes.END_ROD,
                                    wp.getX() + 0.5,
                                    wp.getY() + (i * 2) + (client.player.tickCount % 20) / 10.0,
                                    wp.getZ() + 0.5,
                                    0, 0.05, 0
                                );
                            }
                        }
                    }
                }
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Determine which server/world we joined and load cached map data
            ClientMapStorage.setCurrentServer();
            client.execute(() -> {
                ClientMapManager.clear();
                // Load previously saved map data for this server from client disk
                ClientMapStorage.loadAllIntoManager();
            });

            // If the server also has our mod, request live updates
            if (ClientPlayNetworking.canSend(HandshakePayload.ID)) {
                ClientPlayNetworking.send(new HandshakePayload());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Clear in-memory data; disk data persists for next session
            ClientMapManager.clear();
            ClientMapStorage.clearCurrentServer();
        });

        ClientPlayNetworking.registerGlobalReceiver(MapUpdatePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Update in-memory display
                ClientMapManager.receiveUpdate(payload.chunkX(), payload.chunkZ(), payload.colors());
                // Also persist to client disk for offline/future use
                ClientMapStorage.saveChunk(payload.chunkX(), payload.chunkZ(), payload.colors());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(PlayerPosPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientMapManager.updatePlayerPositions(payload.positions());
            });
        });
    }
}
