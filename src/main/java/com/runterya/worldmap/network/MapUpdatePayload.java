package com.runterya.worldmap.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MapUpdatePayload(String dimension, int chunkX, int chunkZ, int[] colors) implements CustomPacketPayload {
    public static final Type<MapUpdatePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("worldmap", "map_update"));
    public static final StreamCodec<FriendlyByteBuf, MapUpdatePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeUtf(payload.dimension());
            buf.writeInt(payload.chunkX());
            buf.writeInt(payload.chunkZ());
            for (int color : payload.colors()) {
                buf.writeInt(color);
            }
        },
        buf -> {
            String dimension = buf.readUtf();
            int cx = buf.readInt();
            int cz = buf.readInt();
            int[] colors = new int[256];
            for (int i = 0; i < 256; i++) {
                colors[i] = buf.readInt();
            }
            return new MapUpdatePayload(dimension, cx, cz, colors);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
