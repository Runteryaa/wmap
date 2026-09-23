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
        int resolve(LevelChunk chunk, BlockPos pos, MapColor mapColor);
    }

    public static int[] extract(LevelChunk chunk) {
        // The dedicated server has no client biome colormap resources. Leave
        // biome-tinted pixels at their MapColor base; clients replace them with
        // the vanilla block tint once they have the chunk loaded.
        return extract(chunk, (ignoredChunk, ignoredPos, ignoredMapColor) -> -1);
    }

    /**
     * Extracts a chunk while letting the caller use its own vanilla color
     * resolvers. Dedicated servers do not load the client color resources, so
     * the client passes Minecraft's block tint sources here.
     */
    public static int[] extract(LevelChunk chunk, BiomeTintResolver tintResolver) {
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

                // Compute base ARGB color based on the MapColor and 3D shading
                int argb = mapColor.calculateARGBColor(brightness);

                // Ask the client for every block's registered tint source.
                // New blocks can use fixed or custom tints even when their
                // MapColor is not one of the three classic biome categories.
                int tint = tintResolver.resolve(chunk, pos, mapColor);
                if (tint != -1 && tint != 0xFFFF00FF) {
                    argb = applyBrightness(tint, brightness);
                }

                if (mapColor == MapColor.WATER) {
                    // Keep the map's water-depth relief, after applying
                    // Minecraft's biome water color.
                    int oceanFloorY = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                    int depth = Math.max(0, pos.getY() - oceanFloorY);
                    float factor = Math.max(0.4f, 1.0f - (depth * 0.04f));
                    argb = darkenColor(argb, factor);
                }

                colors[z * 16 + x] = argb;
            }
        }
        return colors;
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
