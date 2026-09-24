package com.runterya.worldmap.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Optional creator metadata, sent separately so older clients keep decoding waypoint syncs. */
public record SyncGlobalWaypointOwnersPayload(List<WaypointOwner> owners) implements CustomPacketPayload {
    public static final Type<SyncGlobalWaypointOwnersPayload> ID = new Type<>(
        Identifier.fromNamespaceAndPath("worldmap", "sync_global_waypoint_owners"));

    public record WaypointOwner(String waypointId, String creatorUuid, String creatorName) {}

    public static final StreamCodec<FriendlyByteBuf, SyncGlobalWaypointOwnersPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeInt(payload.owners().size());
            for (WaypointOwner owner : payload.owners()) {
                buf.writeUtf(owner.waypointId());
                buf.writeUtf(owner.creatorUuid());
                buf.writeUtf(owner.creatorName());
            }
        },
        buf -> {
            int size = buf.readInt();
            List<WaypointOwner> owners = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                owners.add(new WaypointOwner(buf.readUtf(), buf.readUtf(), buf.readUtf()));
            }
            return new SyncGlobalWaypointOwnersPayload(owners);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
