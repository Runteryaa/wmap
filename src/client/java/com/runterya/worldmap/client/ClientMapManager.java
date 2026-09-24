package com.runterya.worldmap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Map;
import com.runterya.worldmap.network.PlayerPosPayload.PlayerPos;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.runterya.worldmap.network.MapColorReportPayload;

public class ClientMapManager {
    private static final Map<ChunkPos, RegionTexture> regions = new ConcurrentHashMap<>();
    private static List<PlayerPos> otherPlayers = Collections.emptyList();
    private static final Queue<LevelChunk> pendingChunks = new ConcurrentLinkedQueue<>();
    private static final Set<LevelChunk> pendingChunkSet = ConcurrentHashMap.newKeySet();
    /** Chunks that loaded before the server handshake can be re-reported on join. */
    private static final Set<LevelChunk> loadedChunks = ConcurrentHashMap.newKeySet();
    /** Chunks resolved from this client's actual loaded world and tint resources. */
    private static final Set<Long> locallyResolvedChunks = ConcurrentHashMap.newKeySet();

    /** Clear all in-memory map data (call on world disconnect). */
    public static void clear() {
        regions.values().forEach(RegionTexture::close);
        regions.clear();
        otherPlayers = Collections.emptyList();
        pendingChunks.clear();
        pendingChunkSet.clear();
        locallyResolvedChunks.clear();
    }

    /** Queue a client-loaded chunk for vanilla-tinted map extraction. */
    public static void queueChunk(LevelChunk chunk) {
        loadedChunks.add(chunk);
        if (pendingChunkSet.add(chunk)) {
            pendingChunks.offer(chunk);
        }
    }

    /** Remove chunks that leave the client cache. */
    public static void unloadChunk(LevelChunk chunk) {
        loadedChunks.remove(chunk);
        pendingChunkSet.remove(chunk);
        pendingChunks.remove(chunk);
    }

    /** Re-extract chunks that were already loaded when the server handshake completed. */
    public static void queueLoadedChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        loadedChunks.removeIf(chunk -> chunk.getLevel() != minecraft.level);
        for (LevelChunk chunk : loadedChunks) {
            queueChunk(chunk);
        }
    }

    /** Drop tracked chunk references when leaving a world. */
    public static void forgetLoadedChunks() {
        loadedChunks.clear();
    }

    /** Process a small number per tick to avoid freezing while chunks stream in. */
    public static void processPendingChunks(int limit) {
        Minecraft minecraft = Minecraft.getInstance();
        for (int processed = 0; processed < limit; processed++) {
            LevelChunk chunk = pendingChunks.poll();
            if (chunk == null) return;
            pendingChunkSet.remove(chunk);

            if (minecraft.level == null || chunk.getLevel() != minecraft.level) continue;

            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            int[] colors = ClientMapColorExtractor.extract(chunk);
            receiveLocalUpdate(chunkX, chunkZ, colors);
            ClientMapStorage.saveChunk(chunkX, chunkZ, colors);

            if (ClientPlayNetworking.canSend(MapColorReportPayload.ID)) {
                ClientPlayNetworking.send(new MapColorReportPayload(chunkX, chunkZ, colors));
            }
        }
    }

    public static void receiveUpdate(int chunkX, int chunkZ, int[] colors) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        ChunkPos regionPos = new ChunkPos(regionX, regionZ);

        RegionTexture region = regions.computeIfAbsent(regionPos, RegionTexture::new);
        region.updateChunk(chunkX & 31, chunkZ & 31, colors);
    }

    /** Apply a locally extracted chunk and protect it from stale network copies. */
    public static void receiveLocalUpdate(int chunkX, int chunkZ, int[] colors) {
        locallyResolvedChunks.add(chunkKey(chunkX, chunkZ));
        receiveUpdate(chunkX, chunkZ, colors);
    }

    /**
     * Server data fills unexplored areas, but a loaded chunk's local extraction
     * is newer and must not be replaced by a delayed packet from another source.
     */
    public static boolean receiveServerUpdate(int chunkX, int chunkZ, int[] colors) {
        if (locallyResolvedChunks.contains(chunkKey(chunkX, chunkZ))) return false;
        receiveUpdate(chunkX, chunkZ, colors);
        return true;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }

    public static void updatePlayerPositions(List<PlayerPos> positions) {
        otherPlayers = positions;
    }

    public static List<PlayerPos> getOtherPlayers() {
        return otherPlayers;
    }

    public static Map<ChunkPos, RegionTexture> getRegions() {
        return regions;
    }

    public static RegionTexture getRegion(int regionX, int regionZ) {
        return regions.get(new ChunkPos(regionX, regionZ));
    }

    public static class RegionTexture {
        private final ChunkPos regionPos;
        private final int rx;
        private final int rz;
        private final NativeImage image;
        private DynamicTexture texture;
        private Identifier textureLocation;
        private boolean isDirty = false;

        public RegionTexture(ChunkPos regionPos) {
            this.regionPos = regionPos;
            this.rx = regionPos.getMinBlockX() >> 4;
            this.rz = regionPos.getMinBlockZ() >> 4;
            this.image = new NativeImage(NativeImage.Format.RGBA, 512, 512, true);
        }

        public void updateChunk(int localChunkX, int localChunkZ, int[] colors) {
            int startX = localChunkX * 16;
            int startZ = localChunkZ * 16;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int argb = colors[z * 16 + x];
                    // Ensure opaque
                    int a = (argb >> 24) & 0xFF;
                    if (a == 0) a = 0xFF;
                    argb = (a << 24) | (argb & 0xFFFFFF);
                    
                    // NativeImage expects ARGB in modern versions
                    this.image.setPixel(startX + x, startZ + z, argb);
                }
            }
            this.isDirty = true;
        }

        public Identifier getTextureLocation() {
            if (this.texture == null) {
                this.texture = new DynamicTexture(() -> "worldmap_region", this.image);
                this.textureLocation = Identifier.fromNamespaceAndPath("worldmap", "region_" + rx + "_" + rz);
                Minecraft.getInstance().getTextureManager().register(this.textureLocation, this.texture);
            }
            if (this.isDirty) {
                this.texture.upload();
                this.isDirty = false;
            }
            return this.textureLocation;
        }

        public void close() {
            if (this.texture != null) {
                this.texture.close();
            } else {
                this.image.close();
            }
        }
    }
}
