package com.runterya.worldmap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.runterya.worldmap.network.HandshakePayload;
import com.runterya.worldmap.network.MapUpdatePayload;
import com.runterya.worldmap.network.PlayerPosPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldMapMod implements ModInitializer {
    public static final String MOD_ID = "worldmap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("WorldMap initializing...");

        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.ID, HandshakePayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(MapUpdatePayload.ID, MapUpdatePayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(PlayerPosPayload.ID, PlayerPosPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(com.runterya.worldmap.network.AddGlobalWaypointPayload.ID, com.runterya.worldmap.network.AddGlobalWaypointPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(com.runterya.worldmap.network.SyncGlobalWaypointsPayload.ID, com.runterya.worldmap.network.SyncGlobalWaypointsPayload.CODEC);

        com.runterya.worldmap.backend.WorldMapServer.init();
        LOGGER.info("World Map loaded!");
    }
}
