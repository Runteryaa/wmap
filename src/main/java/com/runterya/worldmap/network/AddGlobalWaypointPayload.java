package com.runterya.worldmap.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AddGlobalWaypointPayload(Action action, String id, String name, int x, int y, int z, int color,
                                       String dimension, WaypointIcon icon) implements CustomPacketPayload {
    public enum Action { ADD, UPDATE, REMOVE }
    public static final CustomPacketPayload.Type<AddGlobalWaypointPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("worldmap", "add_global_waypoint"));

    public AddGlobalWaypointPayload(Action action, String id, String name, int x, int y, int z, int color, String dimension) {
        this(action, id, name, x, y, z, color, dimension, WaypointIcon.NONE);
    }

    public AddGlobalWaypointPayload {
        if (icon == null) icon = WaypointIcon.NONE;
    }
    
    public static final StreamCodec<RegistryFriendlyByteBuf, AddGlobalWaypointPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeEnum(payload.action);
            buf.writeUtf(payload.id);
            buf.writeUtf(payload.name);
            buf.writeInt(payload.x);
            buf.writeInt(payload.y);
            buf.writeInt(payload.z);
            buf.writeInt(payload.color);
            buf.writeUtf(payload.dimension);
            buf.writeEnum(payload.icon);
        },
        buf -> new AddGlobalWaypointPayload(
            buf.readEnum(Action.class),
            buf.readUtf(),
            buf.readUtf(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readUtf(),
            buf.readEnum(WaypointIcon.class)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
