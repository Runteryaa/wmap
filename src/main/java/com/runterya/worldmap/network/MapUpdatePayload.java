package com.runterya.worldmap.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record MapUpdatePayload(String dimension, int chunkX, int chunkZ, int[] colors, List<UUID> explorers) implements CustomPacketPayload {
    public static final Type<MapUpdatePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("worldmap", "map_update"));
    public static final StreamCodec<FriendlyByteBuf, MapUpdatePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeUtf(payload.dimension());
            buf.writeInt(payload.chunkX());
            buf.writeInt(payload.chunkZ());
            buf.writeInt(payload.explorers().size());
            for (UUID explorer : payload.explorers()) {
                buf.writeUUID(explorer);
            }
            for (int color : payload.colors()) {
                buf.writeInt(color);
            }
        },
        buf -> {
            String dimension = buf.readUtf();
            int cx = buf.readInt();
            int cz = buf.readInt();
            int explorerCount = buf.readInt();
            if (explorerCount < 0 || explorerCount > 4096) {
                throw new IllegalArgumentException("Invalid map explorer count: " + explorerCount);
            }
            List<UUID> explorers = new ArrayList<>(explorerCount);
            for (int i = 0; i < explorerCount; i++) explorers.add(buf.readUUID());
            int[] colors = new int[256];
            for (int i = 0; i < 256; i++) {
                colors[i] = buf.readInt();
            }
            return new MapUpdatePayload(dimension, cx, cz, colors, explorers);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
