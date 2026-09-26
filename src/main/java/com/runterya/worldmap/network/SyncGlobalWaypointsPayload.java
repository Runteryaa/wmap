package com.runterya.worldmap.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;

public record SyncGlobalWaypointsPayload(List<GlobalWaypoint> waypoints) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncGlobalWaypointsPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("worldmap", "sync_global_waypoints"));
    
    public record GlobalWaypoint(String id, String name, int x, int y, int z, int color, String dimension,
                                 String icon, String category, String note, String creatorUuid, String creatorName) {
        public GlobalWaypoint(String id, String name, int x, int y, int z, int color, String dimension) {
            this(id, name, x, y, z, color, dimension, "", "", "", "", "");
        }

        public GlobalWaypoint(String id, String name, int x, int y, int z, int color, String dimension, String icon) {
            this(id, name, x, y, z, color, dimension, icon, "", "", "", "");
        }

        public GlobalWaypoint(String id, String name, int x, int y, int z, int color, String dimension,
                              String icon, String creatorUuid, String creatorName) {
            this(id, name, x, y, z, color, dimension, icon, "", "", creatorUuid, creatorName);
        }

        public GlobalWaypoint {
            icon = WaypointIcon.normalize(icon);
            category = category == null ? "" : category.trim();
            if (category.length() > 48) category = category.substring(0, 48);
            note = note == null ? "" : note.trim();
            if (note.length() > 256) note = note.substring(0, 256);
            creatorUuid = creatorUuid == null ? "" : creatorUuid;
            creatorName = creatorName == null ? "" : creatorName;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncGlobalWaypointsPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeInt(payload.waypoints.size());
            for (GlobalWaypoint wp : payload.waypoints) {
                buf.writeUtf(wp.id == null ? "" : wp.id);
                buf.writeUtf(wp.name);
                buf.writeInt(wp.x);
                buf.writeInt(wp.y);
                buf.writeInt(wp.z);
                buf.writeInt(wp.color);
                buf.writeUtf(wp.dimension);
                buf.writeUtf(wp.icon);
                buf.writeUtf(wp.category);
                buf.writeUtf(wp.note);
            }
        },
        buf -> {
            int size = buf.readInt();
            List<GlobalWaypoint> list = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                list.add(new GlobalWaypoint(
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf(),
                    "",
                    ""
                ));
            }
            return new SyncGlobalWaypointsPayload(list);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
