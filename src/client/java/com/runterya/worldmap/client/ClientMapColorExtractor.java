package com.runterya.worldmap.client;

import com.runterya.worldmap.backend.MapColorExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

/** Samples visible block textures and combines them with vanilla client biome tints. */
public final class ClientMapColorExtractor {
    private ClientMapColorExtractor() {}

    public static int[] extract(LevelChunk chunk) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return MapColorExtractor.extract(chunk);
        }

        return MapColorExtractor.extract(chunk, (ignoredChunk, pos, state, mapColor) -> {
            BlockTintSource tintSource = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
            return tintSource == null ? -1 : tintSource.colorInWorld(state, level, pos);
        }, ClientMapColorExtractor::averageTopTextureColor);
    }

    private static int averageTopTextureColor(BlockState state, BlockPos pos) {
        if (state.isAir()) return -1;

        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        BlockStateModel model = blockRenderer.getBlockModel(state);
        var parts = model.getParts(RandomSource.create(pos.asLong()));
        TextureAtlasSprite sprite = null;
        for (BlockModelPart part : parts) {
            var topQuads = part.getQuads(Direction.UP);
            if (!topQuads.isEmpty()) {
                sprite = topQuads.getFirst().getSprite();
                break;
            }
        }
        if (sprite == null) sprite = model.particleIcon();
        if (sprite == null) return -1;

        NativeImage image = sprite.contents().getOriginalImage();
        long red = 0;
        long green = 0;
        long blue = 0;
        int opaquePixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgba = image.getPixelRGBA(x, y);
                if ((rgba >>> 24) == 0) continue;

                // NativeImage packs little-endian RGBA pixels as AABBGGRR.
                red += rgba & 0xFF;
                green += (rgba >>> 8) & 0xFF;
                blue += (rgba >>> 16) & 0xFF;
                opaquePixels++;
            }
        }

        if (opaquePixels == 0) return -1;
        return 0xFF000000
            | ((int) (red / opaquePixels) << 16)
            | ((int) (green / opaquePixels) << 8)
            | (int) (blue / opaquePixels);
    }
}
