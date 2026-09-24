package com.runterya.worldmap.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public record PlayerPosPayload(List<PlayerPos> positions) implements CustomPacketPayload {
    public static final Type<PlayerPosPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("worldmap", "player_pos"));
    public static final StreamCodec<FriendlyByteBuf, PlayerPosPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeInt(payload.positions().size());
            for (PlayerPos pos : payload.positions()) {
                buf.writeUUID(pos.uuid());
                buf.writeDouble(pos.x());
                buf.writeDouble(pos.z());
                buf.writeFloat(pos.yaw());
                buf.writeUtf(pos.name());
                buf.writeUtf(pos.dimension());
            }
        },
        buf -> {
            int size = buf.readInt();
            List<PlayerPos> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(new PlayerPos(buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readUtf(), buf.readUtf()));
            }
            return new PlayerPosPayload(list);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record PlayerPos(UUID uuid, double x, double z, float yaw, String name, String dimension) {}
}
