package com.runterya.worldmap.backend;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

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
        // Biome water color is world data, so the dedicated server can resolve
        // it too. Using the same tint on both sides prevents unexplored chunks
        // from appearing as bright vanilla MapColor blue until a client report
        // replaces them.
        return extract(chunk, (sourceChunk, pos, ignoredState, mapColor) -> {
            if (mapColor == MapColor.WATER) {
                return 0xFF000000 | (sourceChunk.getLevel().getBiome(pos).value().getWaterColor() & 0xFFFFFF);
            }
            return -1;
        });
    }

    /**
     * Extracts a chunk while letting the caller use its own vanilla color
     * resolvers. Dedicated servers do not load the client color resources, so
     * the client passes Minecraft's block tint sources here.
     */
    public static int[] extract(LevelChunk chunk, BiomeTintResolver tintResolver) {
        return extract(chunk, tintResolver, (state, pos) -> -1);
    }

    /** Extracts colors from block textures, biome tints, map colors, and terrain shading. */
    public static int[] extract(LevelChunk chunk, BiomeTintResolver tintResolver, BlockTextureColorResolver textureResolver) {
        int[] colors = new int[256];
        for (int x = 0; x < 16; x++) {
            int prevY = -1;
            for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                
                MapColor mapColor = MapColor.NONE;
                BlockState state = chunk.getBlockState(pos);
                while (pos.getY() > chunk.getMinY()) {
                    state = chunk.getBlockState(pos);
                    mapColor = state.getMapColor(chunk.getLevel(), pos);
                    if (mapColor != MapColor.NONE) {
                        break;
                    }
                    pos.move(0, -1, 0);
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

                int tint = tintResolver.resolve(chunk, pos, state, mapColor);
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

                if (isWater || isLava) {
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
