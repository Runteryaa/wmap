package com.runterya.worldmap.mixin;

import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    private static final net.minecraft.resources.Identifier BEAM_TEXTURE = net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/beacon_beam.png");

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderLevel(com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator, net.minecraft.client.DeltaTracker deltaTracker, boolean renderBlockOutline, net.minecraft.client.renderer.state.level.CameraRenderState cameraState, org.joml.Matrix4fc frustumMatrix, com.mojang.blaze3d.buffers.GpuBufferSlice gpuBufferSlice, org.joml.Vector4f fogColor, boolean isFoggy, net.minecraft.client.renderer.chunk.ChunkSectionsToRender chunks, CallbackInfo ci) {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        
        String currentDim = client.level.dimension().identifier().toString();
        net.minecraft.world.phys.Vec3 camPos = cameraState.pos;
        
        net.minecraft.client.renderer.MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
        net.minecraft.client.renderer.rendertype.RenderType renderType = net.minecraft.client.renderer.rendertype.RenderTypes.beaconBeam(BEAM_TEXTURE, true);
        com.mojang.blaze3d.vertex.VertexConsumer consumer = bufferSource.getBuffer(renderType);
        
        long time = client.level.getGameTime();
        
        for (Waypoint wp : WaypointManager.getWaypoints()) {
            if (!wp.getDimension().equals(currentDim)) continue;
            
            double distSq = client.player.distanceToSqr(wp.getX(), client.player.getY(), wp.getZ());
            if (distSq < 16384) {
                float x = (float) (wp.getX() + 0.5 - camPos.x);
                float z = (float) (wp.getZ() + 0.5 - camPos.z);
                float y0 = (float) (wp.getY() - camPos.y);
                float y1 = (float) (320 - camPos.y);
                
                int r = (wp.getColor() >> 16) & 0xFF;
                int g = (wp.getColor() >> 8) & 0xFF;
                int b = wp.getColor() & 0xFF;
                int a = 200;
                
                float w = 0.2f;
                float v0 = (float) Math.floorMod(time, 40) / 40.0f;
                float v1 = v0 + (y1 - y0) / 4.0f;
                
                // South
                consumer.addVertex(x - w, y0, z + w).setColor(r, g, b, a).setUv(1.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y0, z + w).setColor(r, g, b, a).setUv(0.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y1, z + w).setColor(r, g, b, a).setUv(0.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x - w, y1, z + w).setColor(r, g, b, a).setUv(1.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                
                // North
                consumer.addVertex(x + w, y0, z - w).setColor(r, g, b, a).setUv(1.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x - w, y0, z - w).setColor(r, g, b, a).setUv(0.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x - w, y1, z - w).setColor(r, g, b, a).setUv(0.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y1, z - w).setColor(r, g, b, a).setUv(1.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                
                // East
                consumer.addVertex(x + w, y0, z + w).setColor(r, g, b, a).setUv(1.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y0, z - w).setColor(r, g, b, a).setUv(0.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y1, z - w).setColor(r, g, b, a).setUv(0.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y1, z + w).setColor(r, g, b, a).setUv(1.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                
                // West
                consumer.addVertex(x - w, y0, z - w).setColor(r, g, b, a).setUv(1.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x - w, y0, z + w).setColor(r, g, b, a).setUv(0.0f, v1).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x - w, y1, z + w).setColor(r, g, b, a).setUv(0.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x - w, y1, z - w).setColor(r, g, b, a).setUv(1.0f, v0).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                
                // Top
                consumer.addVertex(x - w, y1, z - w).setColor(r, g, b, a).setUv(0.0f, 0.0f).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x - w, y1, z + w).setColor(r, g, b, a).setUv(0.0f, 1.0f).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y1, z + w).setColor(r, g, b, a).setUv(1.0f, 1.0f).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                consumer.addVertex(x + w, y1, z - w).setColor(r, g, b, a).setUv(1.0f, 0.0f).setOverlay(15728880).setLight(15728880).setNormal(0, 1, 0);
                
                // Bottom
                consumer.addVertex(x - w, y0, z + w).setColor(r, g, b, a).setUv(0.0f, 1.0f).setOverlay(15728880).setLight(15728880).setNormal(0, -1, 0);
                consumer.addVertex(x - w, y0, z - w).setColor(r, g, b, a).setUv(0.0f, 0.0f).setOverlay(15728880).setLight(15728880).setNormal(0, -1, 0);
                consumer.addVertex(x + w, y0, z - w).setColor(r, g, b, a).setUv(1.0f, 0.0f).setOverlay(15728880).setLight(15728880).setNormal(0, -1, 0);
                consumer.addVertex(x + w, y0, z + w).setColor(r, g, b, a).setUv(1.0f, 1.0f).setOverlay(15728880).setLight(15728880).setNormal(0, -1, 0);
            }
        }
        
        // Flush the buffer to ensure the beam is drawn immediately
        bufferSource.endBatch(renderType);
    }
}
