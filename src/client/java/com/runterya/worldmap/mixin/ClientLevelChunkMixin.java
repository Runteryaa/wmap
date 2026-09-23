package com.runterya.worldmap.mixin;

import com.runterya.worldmap.client.ClientMapManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rebuilds the local map after a visible chunk changes on a client-only install. */
@Mixin(LevelChunk.class)
public class ClientLevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void worldmap$onClientBlockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel().isClientSide() && cir.getReturnValue() != null) {
            ClientMapManager.queueChunk(chunk);
        }
    }
}
