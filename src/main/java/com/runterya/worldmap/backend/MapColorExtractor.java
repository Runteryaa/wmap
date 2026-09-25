package com.runterya.worldmap.backend;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

public class MapColorExtractor {
    @FunctionalInterface
    public interface BiomeTintResolver {
        /**
         * Returns the block's tint, or -1 when it has no tint or cannot be
         * resolved in the current environment.
         */
        int resolve(LevelChunk chunk, BlockPos pos, BlockState state, MapColor mapColor);
    }

    @FunctionalInterface
    public interface BlockTextureColorResolver {
        /** Returns an opaque average of the visible top-face texture, or -1 if unavailable. */
        int resolve(BlockState state, BlockPos pos);
    }

    public static int[] extract(LevelChunk chunk) {
        return extract(chunk, (sourceChunk, pos, ignoredState, mapColor) -> -1, (state, pos) -> -1, false);
    }

    /**
     * Extracts a chunk while letting the caller use its own vanilla color
     * resolvers. Dedicated servers do not load the client color resources, so
     * the client passes Minecraft's block tint sources here.
     */
    public static int[] extract(LevelChunk chunk, BiomeTintResolver tintResolver) {
        return extract(chunk, tintResolver, (state, pos) -> -1, false);
    }

    /** Extracts colors from block textures, biome tints, map colors, and terrain shading. */
    public static int[] extract(LevelChunk chunk, BiomeTintResolver tintResolver, BlockTextureColorResolver textureResolver) {
        return extract(chunk, tintResolver, textureResolver, false);
    }

    /** Extracts the Nether's mid-level view when requested; other dimensions retain the current surface view. */
    public static int[] extract(LevelChunk chunk, BiomeTintResolver tintResolver,
                                BlockTextureColorResolver textureResolver, boolean netherMidLevel) {
        int[] colors = new int[256];
        WaterBiomeTint waterTint = new WaterBiomeTint(chunk);
        boolean fixedNetherSlice = netherMidLevel && NetherMapView.NETHER_DIMENSION.equals(
            chunk.getLevel().dimension().identifier().toString());
        int netherLogicalHeight = fixedNetherSlice
            ? chunk.getLevel().dimensionType().logicalHeight()
            : 0;
        int netherMidY = fixedNetherSlice
            ? NetherMapView.getMidLevelY(chunk.getMinY(), chunk.getMaxY(), netherLogicalHeight)
            : 0;
        int netherMinY = fixedNetherSlice
            ? NetherMapView.getMidLevelMinY(chunk.getMinY(), chunk.getMaxY(), netherLogicalHeight)
            : 0;
        int netherMaxY = fixedNetherSlice
            ? NetherMapView.getMidLevelMaxY(chunk.getMinY(), chunk.getMaxY(), netherLogicalHeight)
            : -1;
        for (int x = 0; x < 16; x++) {
            int prevY = -1;
            for (int z = 0; z < 16; z++) {
                int y = fixedNetherSlice
                    ? netherMidY
                    : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                
                MapColor mapColor = MapColor.NONE;
                BlockState state = chunk.getBlockState(pos);
                if (fixedNetherSlice) {
                    // Use the nearest non-air block in the bounded band around
                    // the logical-height midpoint; fluids are valid samples too.
                    boolean foundBlock = false;
                    for (int offset = 0; !foundBlock && offset <= NetherMapView.MID_LEVEL_BAND_RADIUS; offset++) {
                        int aboveY = y + offset;
                        if (aboveY <= netherMaxY) {
                            pos.set(chunk.getPos().getMinBlockX() + x, aboveY,
                                chunk.getPos().getMinBlockZ() + z);
                            state = chunk.getBlockState(pos);
                            if (!state.isAir() && !state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                                mapColor = state.getMapColor(chunk.getLevel(), pos);
                                foundBlock = true;
                                break;
                            }
                        }

                        if (offset > 0) {
                            int belowY = y - offset;
                            if (belowY >= netherMinY) {
                                pos.set(chunk.getPos().getMinBlockX() + x, belowY,
                                    chunk.getPos().getMinBlockZ() + z);
                                state = chunk.getBlockState(pos);
                                if (!state.isAir() && !state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                                    mapColor = state.getMapColor(chunk.getLevel(), pos);
                                    foundBlock = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!foundBlock) {
                        colors[z * 16 + x] = 0;
                        continue;
                    }
                } else {
                    while (pos.getY() > chunk.getMinY()) {
                        state = chunk.getBlockState(pos);
                        mapColor = state.getMapColor(chunk.getLevel(), pos);
                        if (mapColor != MapColor.NONE) {
                            break;
                        }
                        pos.move(0, -1, 0);
                    }
                }

                MapColor.Brightness brightness = MapColor.Brightness.NORMAL;
                if (z > 0 || prevY != -1) {
                    if (pos.getY() > prevY) {
                        brightness = MapColor.Brightness.HIGH;
                    } else if (pos.getY() < prevY) {
                        brightness = MapColor.Brightness.LOW;
                    }
                }
                prevY = pos.getY();
                boolean isWater = mapColor == MapColor.WATER;
                boolean isLava = state.getFluidState().is(FluidTags.LAVA);

                // Fluid surfaces use their own depth shading. Applying terrain
                // slope shading as well creates abrupt patches across a pool.
                MapColor.Brightness renderedBrightness = isWater || isLava
                    ? MapColor.Brightness.NORMAL
                    : brightness;

                // Use the real block texture where the client can resolve it;
                // MapColor remains the safe fallback for dedicated servers.
                int argb = mapColor.calculateARGBColor(renderedBrightness);

                // Both client and server use one deterministic water-color
                // path. Mixing client-blended tints with server raw biome
                // colors made otherwise matching chunks show visible seams.
                int tint = isWater
                    ? waterTint.getColor(pos)
                    : tintResolver.resolve(chunk, pos, state, mapColor);
                if (tint == 0xFFFF00FF) tint = -1;
                int textureColor = isWater ? -1 : textureResolver.resolve(state, pos);

                if (textureColor != -1) {
                    if (tint != -1) {
                        textureColor = multiplyColors(textureColor, tint);
                    }
                    // Apply the map's terrain shade directly; an extra texture
                    // boost washed out stone and other neutral blocks.
                    argb = applyBrightness(textureColor, renderedBrightness);
                } else if (tint != -1) {
                    argb = applyBrightness(tint, renderedBrightness);
                }

                if ((isWater || isLava) && !fixedNetherSlice) {
                    // Count the contiguous fluid column, so both source and
                    // flowing lava receive consistent depth shading.
                    int depth = getFluidDepth(chunk, pos, isLava);
                    // Leave the surface block bright, then darken deeper columns.
                    float darkeningDepth = Math.max(0, depth - SHALLOW_FLUID_BLOCKS);
                    float factor = 1.0f / (1.0f + darkeningDepth * FLUID_DARKENING_PER_BLOCK);
                    argb = darkenColor(argb, factor);
                }

                colors[z * 16 + x] = argb;
            }
        }
        return colors;
    }

    // The first block stays bright; 5/10/20/40/60-block columns retain about
    // 86/74/57/39/30 percent brightness. The curve has no early dark plateau.
    private static final int SHALLOW_FLUID_BLOCKS = 1;
    private static final float FLUID_DARKENING_PER_BLOCK = 0.04f;

    /**
     * Gives water the default vanilla-sized biome blend on both client and
     * server, while replacing only isolated one- or two-block color outliers.
     * This keeps real biome outlines intact and avoids chunk-source seams.
     */
    private static final class WaterBiomeTint {
        private static final int BLEND_RADIUS = 2;
        private static final int OUTLIER_RADIUS = 1;
        private static final int OUTLIER_SAMPLE_COUNT = 9;
        private static final int MAX_CENTER_OUTLIERS = 2;
        private static final int MIN_DOMINANT_NEIGHBORS = 7;

        private final LevelChunk chunk;
        private final Map<Long, Integer> biomeWaterColors = new HashMap<>();
        private final int[] outlierSamples = new int[OUTLIER_SAMPLE_COUNT];
        private final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos outlierPos = new BlockPos.MutableBlockPos();

        private WaterBiomeTint(LevelChunk chunk) {
            this.chunk = chunk;
        }

        private int getColor(BlockPos center) {
            int red = 0;
            int green = 0;
            int blue = 0;
            int sampleCount = 0;
            for (int dz = -BLEND_RADIUS; dz <= BLEND_RADIUS; dz++) {
                for (int dx = -BLEND_RADIUS; dx <= BLEND_RADIUS; dx++) {
                    int color = getOutlierFilteredColor(
                        center.getX() + dx, center.getY(), center.getZ() + dz
                    );
                    red += (color >> 16) & 0xFF;
                    green += (color >> 8) & 0xFF;
                    blue += color & 0xFF;
                    sampleCount++;
                }
            }
            return 0xFF000000
                | ((red / sampleCount) << 16)
                | ((green / sampleCount) << 8)
                | (blue / sampleCount);
        }

        private int getOutlierFilteredColor(int x, int y, int z) {
            int centerColor = getBiomeWaterColor(x, y, z);
            int index = 0;
            for (int dz = -OUTLIER_RADIUS; dz <= OUTLIER_RADIUS; dz++) {
                for (int dx = -OUTLIER_RADIUS; dx <= OUTLIER_RADIUS; dx++) {
                    outlierPos.set(x + dx, y, z + dz);
                    outlierSamples[index++] = getBiomeWaterColor(outlierPos);
                }
            }

            int dominantColor = centerColor;
            int dominantCount = 0;
            int centerCount = 0;
            for (int color : outlierSamples) {
                int count = 0;
                for (int candidate : outlierSamples) {
                    if (candidate == color) count++;
                }
                if (color == centerColor) centerCount = count;
                if (count > dominantCount) {
                    dominantCount = count;
                    dominantColor = color;
                }
            }

            if (centerColor != dominantColor
                && centerCount <= MAX_CENTER_OUTLIERS
                && dominantCount >= MIN_DOMINANT_NEIGHBORS) {
                return dominantColor;
            }
            return centerColor;
        }

        private int getBiomeWaterColor(int x, int y, int z) {
            biomePos.set(x, y, z);
            return getBiomeWaterColor(biomePos);
        }

        private int getBiomeWaterColor(BlockPos pos) {
            long key = pos.asLong();
            Integer color = biomeWaterColors.get(key);
            if (color == null) {
                color = chunk.getLevel().getBiome(pos).value().getWaterColor() & 0xFFFFFF;
                biomeWaterColors.put(key, color);
            }
            return color;
        }
    }

    private static int getFluidDepth(LevelChunk chunk, BlockPos surfacePos, boolean lava) {
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
        int surfaceY = surfacePos.getY();
        for (int y = surfacePos.getY(); y >= chunk.getMinY(); y--) {
            scanPos.set(surfacePos.getX(), y, surfacePos.getZ());
            BlockState state = chunk.getBlockState(scanPos);
            boolean matchingFluid = lava
                ? state.getFluidState().is(FluidTags.LAVA)
                : state.getFluidState().is(FluidTags.WATER);
            if (matchingFluid) {
                continue;
            }

            // Underwater plants and other non-colliding decorations displace
            // water blocks but do not raise the seabed. Keep measuring through
            // them so kelp height cannot make a deep ocean look shallow.
            if (!state.isAir() && state.getCollisionShape(chunk, scanPos).isEmpty()) {
                continue;
            }

            // The first solid block (or an air pocket) ends the fluid depth.
            return surfaceY - y;
        }
        return surfaceY - chunk.getMinY() + 1;
    }

    private static int multiplyColors(int base, int tint) {
        int r = (((base >> 16) & 0xFF) * ((tint >> 16) & 0xFF)) / 255;
        int g = (((base >> 8) & 0xFF) * ((tint >> 8) & 0xFF)) / 255;
        int b = ((base & 0xFF) * (tint & 0xFF)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int applyBrightness(int color, MapColor.Brightness brightness) {
        int modifier = 220; // NORMAL
        if (brightness == MapColor.Brightness.LOW) modifier = 180;
        if (brightness == MapColor.Brightness.HIGH) modifier = 255;
        if (brightness == MapColor.Brightness.LOWEST) modifier = 135;
        
        int r = (((color >> 16) & 0xFF) * modifier) / 255;
        int g = (((color >> 8) & 0xFF) * modifier) / 255;
        int b = ((color & 0xFF) * modifier) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int darkenColor(int color, float factor) {
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

}
