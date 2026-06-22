package com.runterya.worldmap.mixin;

import com.runterya.worldmap.backend.MapColorExtractor;
import com.runterya.worldmap.backend.WorldMapServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void onSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk)(Object)this;
        if (!chunk.getLevel().isClientSide() && cir.getReturnValue() != null) {
            if (WorldMapServer.storage != null && !WorldMapServer.MODDED_PLAYERS.isEmpty()) {
                if (chunk.getLevel() instanceof ServerLevel serverLevel) {
                    WorldMapServer.markChunkDirty(chunk);
                }
            }
        }
    }
}
