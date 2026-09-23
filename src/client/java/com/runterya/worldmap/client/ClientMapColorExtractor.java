package com.runterya.worldmap.client;

import com.runterya.worldmap.backend.MapColorExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

/** Uses the same block tint sources as vanilla's client-side block rendering. */
public final class ClientMapColorExtractor {
    private ClientMapColorExtractor() {}

    public static int[] extract(LevelChunk chunk) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return MapColorExtractor.extract(chunk);
        }

        return MapColorExtractor.extract(chunk, (ignoredChunk, pos, mapColor) -> {
            BlockState state = chunk.getBlockState(pos);
            BlockTintSource tintSource = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
            return tintSource == null ? -1 : tintSource.colorInWorld(state, level, pos);
        });
    }
}
