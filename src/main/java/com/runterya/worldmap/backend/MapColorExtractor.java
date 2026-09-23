package com.runterya.worldmap.backend;

import net.minecraft.core.BlockPos;
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
        // The dedicated server has no client biome colormap resources. Leave
        // biome-tinted pixels at their MapColor base; clients replace them with
        // the vanilla block tint once they have the chunk loaded.
        return extract(chunk, (ignoredChunk, ignoredPos, ignoredState, ignoredMapColor) -> -1);
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

                // Use the real block texture where the client can resolve it;
                // MapColor remains the safe fallback for dedicated servers.
                int argb = mapColor.calculateARGBColor(brightness);

                int tint = tintResolver.resolve(chunk, pos, state, mapColor);
                if (tint == 0xFFFF00FF) tint = -1;
                int textureColor = mapColor == MapColor.WATER ? -1 : textureResolver.resolve(state, pos);

                if (textureColor != -1) {
                    if (tint != -1) {
                        textureColor = multiplyColors(textureColor, tint);
                    }
                    // Texture averages are less saturated/darker than the rendered
                    // block face after map-style shading, so compensate before shade.
                    argb = applyBrightness(scaleColor(textureColor, 1.2f), brightness);
                } else if (tint != -1) {
                    argb = applyBrightness(tint, brightness);
                }

                if (mapColor == MapColor.WATER) {
                    // Keep the map's water-depth relief, after applying
                    // Minecraft's biome water color.
                    int waterSurfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    int oceanFloorY = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                    int depth = Math.max(0, waterSurfaceY - oceanFloorY);
                    float factor = Math.max(MIN_WATER_BRIGHTNESS, (float) Math.pow(WATER_DARKENING_PER_BLOCK, depth));
                    argb = darkenColor(argb, factor);
                }

                colors[z * 16 + x] = argb;
            }
        }
        return colors;
    }

    private static final float WATER_DARKENING_PER_BLOCK = 0.985f;
    private static final float MIN_WATER_BRIGHTNESS = 0.2f;

    private static int multiplyColors(int base, int tint) {
        int r = (((base >> 16) & 0xFF) * ((tint >> 16) & 0xFF)) / 255;
        int g = (((base >> 8) & 0xFF) * ((tint >> 8) & 0xFF)) / 255;
        int b = ((base & 0xFF) * (tint & 0xFF)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int scaleColor(int color, float scale) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * scale));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * scale));
        int b = Math.min(255, Math.round((color & 0xFF) * scale));
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
