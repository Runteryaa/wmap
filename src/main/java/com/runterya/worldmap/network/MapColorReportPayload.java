package com.runterya.worldmap.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-reported map colors, resolved with Minecraft's client block tint sources. */
public record MapColorReportPayload(int chunkX, int chunkZ, int[] colors) implements CustomPacketPayload {
    public static final Type<MapColorReportPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("worldmap", "map_color_report"));
    public static final StreamCodec<FriendlyByteBuf, MapColorReportPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeInt(payload.chunkX());
            buf.writeInt(payload.chunkZ());
            for (int color : payload.colors()) {
                buf.writeInt(color);
            }
        },
        buf -> {
            int cx = buf.readInt();
            int cz = buf.readInt();
            int[] colors = new int[256];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = buf.readInt();
            }
            return new MapColorReportPayload(cx, cz, colors);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
