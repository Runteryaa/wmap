package com.runterya.worldmap.client;

import com.runterya.worldmap.backend.MapColorExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Samples visible block textures and combines them with vanilla client biome tints. */
public final class ClientMapColorExtractor {
    private static final ConcurrentHashMap<Identifier, Integer> TEXTURE_COLORS = new ConcurrentHashMap<>();
    /** Water-only median filter radius; suppresses tiny biome-color islands without flattening depth. */
    private static final int WATER_BIOME_FILTER_RADIUS = 4;
    private static final int WATER_BIOME_FILTER_SIZE = WATER_BIOME_FILTER_RADIUS * 2 + 1;
    private static final int WATER_BIOME_FILTER_SAMPLE_COUNT = WATER_BIOME_FILTER_SIZE * WATER_BIOME_FILTER_SIZE;

    private ClientMapColorExtractor() {}

    public static int[] extract(LevelChunk chunk) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return MapColorExtractor.extract(chunk);
        }

        Map<Long, Integer> waterTintSamples = new HashMap<>();
        int[] waterTintWindow = new int[WATER_BIOME_FILTER_SAMPLE_COUNT];
        int[] waterTintReds = new int[WATER_BIOME_FILTER_SAMPLE_COUNT];
        int[] waterTintGreens = new int[WATER_BIOME_FILTER_SAMPLE_COUNT];
        int[] waterTintBlues = new int[WATER_BIOME_FILTER_SAMPLE_COUNT];
        return MapColorExtractor.extract(chunk, (ignoredChunk, pos, state, mapColor) -> {
            if (mapColor == MapColor.WATER) {
                // Start with vanilla's biome blend, then discard tiny isolated
                // water-color patches by selecting the neighborhood's median
                // tint. This changes only the biome tint; depth shading is
                // applied later by MapColorExtractor and remains per-block.
                return filteredWaterTint(level, pos, waterTintSamples, waterTintWindow,
                    waterTintReds, waterTintGreens, waterTintBlues);
            }
            BlockTintSource tintSource = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
            return tintSource == null ? -1 : tintSource.colorInWorld(state, level, pos);
        }, ClientMapColorExtractor::averageTopTextureColor);
    }

    private static int filteredWaterTint(ClientLevel level, BlockPos center,
                                         Map<Long, Integer> sampleCache, int[] samples, int[] reds,
                                         int[] greens, int[] blues) {
        int centerTint = cachedWaterTint(level, center, sampleCache);
        int index = 0;
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        for (int dz = -WATER_BIOME_FILTER_RADIUS; dz <= WATER_BIOME_FILTER_RADIUS; dz++) {
            for (int dx = -WATER_BIOME_FILTER_RADIUS; dx <= WATER_BIOME_FILTER_RADIUS; dx++) {
                samplePos.set(center.getX() + dx, center.getY(), center.getZ() + dz);
                // Unknown chunks can return placeholder biome colors. Treat
                // those samples as the center color instead of painting a
                // false biome patch at the edge of loaded chunk data.
                samples[index++] = level.hasChunkAt(samplePos)
                    ? cachedWaterTint(level, samplePos, sampleCache)
                    : centerTint;
            }
        }

        for (int i = 0; i < WATER_BIOME_FILTER_SAMPLE_COUNT; i++) {
            reds[i] = (samples[i] >> 16) & 0xFF;
            greens[i] = (samples[i] >> 8) & 0xFF;
            blues[i] = samples[i] & 0xFF;
        }
        Arrays.sort(reds);
        Arrays.sort(greens);
        Arrays.sort(blues);

        int median = WATER_BIOME_FILTER_SAMPLE_COUNT / 2;
        int medianRed = reds[median];
        int medianGreen = greens[median];
        int medianBlue = blues[median];
        int bestColor = samples[0];
        int bestDistance = Integer.MAX_VALUE;
        for (int color : samples) {
            int redDelta = ((color >> 16) & 0xFF) - medianRed;
            int greenDelta = ((color >> 8) & 0xFF) - medianGreen;
            int blueDelta = (color & 0xFF) - medianBlue;
            int distance = redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestColor = color;
            }
        }
        return 0xFF000000 | (bestColor & 0xFFFFFF);
    }

    private static int cachedWaterTint(ClientLevel level, BlockPos pos, Map<Long, Integer> sampleCache) {
        long key = pos.asLong();
        Integer tint = sampleCache.get(key);
        if (tint == null) {
            tint = BiomeColors.getAverageWaterColor(level, pos);
            sampleCache.put(key, tint);
        }
        return tint;
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
