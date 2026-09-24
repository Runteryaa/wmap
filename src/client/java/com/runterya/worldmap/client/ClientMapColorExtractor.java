package com.runterya.worldmap.client;

import com.runterya.worldmap.backend.MapColorExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** Samples visible block textures and combines them with vanilla client biome tints. */
public final class ClientMapColorExtractor {
    private static final ConcurrentHashMap<Identifier, Integer> TEXTURE_COLORS = new ConcurrentHashMap<>();

    private ClientMapColorExtractor() {}

    public static int[] extract(LevelChunk chunk) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return MapColorExtractor.extract(chunk);
        }

        return MapColorExtractor.extract(chunk, (ignoredChunk, pos, state, mapColor) -> {
            if (mapColor == MapColor.WATER) {
                // Match vanilla's biome-blended water tint instead of sampling
                // one raw biome color per block. Raw sampling creates abrupt,
                // patchy color regions at biome boundaries, most noticeable in
                // darker ocean biomes.
                return BiomeColors.getAverageWaterColor(level, pos);
            }
            BlockTintSource tintSource = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
            return tintSource == null ? -1 : tintSource.colorInWorld(state, level, pos);
        }, ClientMapColorExtractor::averageTopTextureColor);
    }

    private static int averageTopTextureColor(BlockState state, BlockPos pos) {
        if (state.isAir()) return -1;

        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
        var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(RandomSource.create(pos.asLong()), parts);
        TextureAtlasSprite sprite = null;
        for (BlockStateModelPart part : parts) {
            var topQuads = part.getQuads(Direction.UP);
            if (!topQuads.isEmpty()) {
                BakedQuad quad = topQuads.getFirst();
                sprite = quad.materialInfo().sprite();
                break;
            }
        }
        if (sprite == null && !parts.isEmpty()) {
            sprite = parts.getFirst().particleMaterial().sprite();
        }
        if (sprite == null) return -1;

        Identifier spriteId = sprite.contents().name();
        int textureColor = TEXTURE_COLORS.computeIfAbsent(spriteId, ClientMapColorExtractor::loadTextureColor);
        return textureColor == 0 ? -1 : textureColor;
    }

    private static int loadTextureColor(Identifier spriteId) {
        Identifier textureId = Identifier.fromNamespaceAndPath(
            spriteId.getNamespace(), "textures/" + spriteId.getPath() + ".png"
        );
        var resource = Minecraft.getInstance().getResourceManager().getResource(textureId);
        if (resource.isEmpty()) return 0;

        try (var input = resource.get().open()) {
            var image = ImageIO.read(input);
            if (image == null) return 0;

            long red = 0;
            long green = 0;
            long blue = 0;
            int opaquePixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgba = image.getRGB(x, y);
                    if ((rgba >>> 24) == 0) continue;

                    red += (rgba >>> 16) & 0xFF;
                    green += (rgba >>> 8) & 0xFF;
                    blue += rgba & 0xFF;
                    opaquePixels++;
                }
            }

            if (opaquePixels == 0) return 0;
            return 0xFF000000
                | ((int) (red / opaquePixels) << 16)
                | ((int) (green / opaquePixels) << 8)
                | (int) (blue / opaquePixels);
        } catch (IOException | RuntimeException ignored) {
            return 0;
        }
    }
}
