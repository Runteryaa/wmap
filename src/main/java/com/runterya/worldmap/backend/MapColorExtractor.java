package com.runterya.worldmap.backend;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;

public class MapColorExtractor {
    public static int[] extract(LevelChunk chunk) {
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
                
                // --- BIOME TINTING & WATER DEPTH ---
                if (mapColor == MapColor.GRASS || mapColor == MapColor.PLANT || mapColor == MapColor.WATER) {
                    Holder<Biome> biomeHolder = chunk.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
                    if (biomeHolder != null && biomeHolder.value() != null) {
                        Biome biome = biomeHolder.value();
                        if (mapColor == MapColor.GRASS) {
                            int tint = biome.getGrassColor(pos.getX(), pos.getZ());
                            if (tint == -65281 || tint == 0) { // Dedicated Server missing colormap
                                tint = approximateColor(biome.getBaseTemperature(), false);
                            }
                            argb = applyBrightness(tint, brightness);
                        } else if (mapColor == MapColor.PLANT) {
                            int tint = biome.getFoliageColor();
                            if (tint == -65281 || tint == 0) {
                                tint = approximateColor(biome.getBaseTemperature(), true);
                            }
                            argb = applyBrightness(tint, brightness);
                        } else if (mapColor == MapColor.WATER) {
                            int tint = biome.getWaterColor();
                            if (tint != -65281 && tint != 0) {
                                argb = applyBrightness(tint, brightness);
                            }
                            
                            // Water depth shading
                            int oceanFloorY = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                            int depth = Math.max(0, pos.getY() - oceanFloorY);
                            float factor = Math.max(0.4f, 1.0f - (depth * 0.04f));
                            argb = darkenColor(argb, factor);
                        }
                    }
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

    private static int approximateColor(float temp, boolean isFoliage) {
        if (temp < 0.2f) return isFoliage ? 0x60A17B : 0x80B497; // Snowy/Ice
        if (temp < 0.5f) return isFoliage ? 0x68A048 : 0x86B783; // Taiga/Cool
        if (temp < 0.85f) return isFoliage ? 0x59AE30 : 0x79C05A; // Plains/Forest
        return isFoliage ? 0x82A82D : 0x90814D; // Desert/Savanna
    }
}
