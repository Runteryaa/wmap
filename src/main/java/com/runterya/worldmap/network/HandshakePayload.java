package com.runterya.worldmap.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HandshakePayload() implements CustomPacketPayload {
    public static final Type<HandshakePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("worldmap", "handshake"));
    public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = StreamCodec.unit(new HandshakePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
