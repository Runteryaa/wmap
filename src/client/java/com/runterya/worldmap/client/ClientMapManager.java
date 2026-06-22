package com.runterya.worldmap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.MapColor;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.Map;
import com.runterya.worldmap.network.PlayerPosPayload.PlayerPos;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class ClientMapManager {
    private static final Map<ChunkPos, RegionTexture> regions = new ConcurrentHashMap<>();
    private static List<PlayerPos> otherPlayers = Collections.emptyList();

    /** Clear all in-memory map data (call on world disconnect). */
    public static void clear() {
        regions.values().forEach(RegionTexture::close);
        regions.clear();
        otherPlayers = Collections.emptyList();
    }

    public static void receiveUpdate(int chunkX, int chunkZ, int[] colors) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        ChunkPos regionPos = new ChunkPos(regionX, regionZ);

        RegionTexture region = regions.computeIfAbsent(regionPos, RegionTexture::new);
        region.updateChunk(chunkX & 31, chunkZ & 31, colors);
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
