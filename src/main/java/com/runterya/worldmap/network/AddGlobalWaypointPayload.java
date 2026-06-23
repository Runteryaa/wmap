package com.runterya.worldmap.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AddGlobalWaypointPayload(String name, int x, int y, int z, int color, String dimension) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AddGlobalWaypointPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("worldmap", "add_global_waypoint"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, AddGlobalWaypointPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeUtf(payload.name);
            buf.writeInt(payload.x);
            buf.writeInt(payload.y);
            buf.writeInt(payload.z);
            buf.writeInt(payload.color);
            buf.writeUtf(payload.dimension);
        },
        buf -> new AddGlobalWaypointPayload(
            buf.readUtf(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readUtf()
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
