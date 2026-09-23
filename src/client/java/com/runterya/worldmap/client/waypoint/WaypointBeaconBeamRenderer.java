package com.runterya.worldmap.client.waypoint;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Renders waypoint markers with Minecraft's own animated beacon beam. */
public final class WaypointBeaconBeamRenderer {
    private static final double MAX_DISTANCE_SQUARED = 512.0 * 512.0;

    private static volatile List<BeamState> extractedBeams = List.of();
    private static volatile List<BeamState> preparedBeams = List.of();

    private WaypointBeaconBeamRenderer() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(WaypointBeaconBeamRenderer::tick);
        LevelRenderEvents.END_EXTRACTION.register(context -> extractedBeams = preparedBeams);
        LevelRenderEvents.COLLECT_SUBMITS.register(WaypointBeaconBeamRenderer::render);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            preparedBeams = List.of();
            return;
        }

        String dimension = minecraft.level.dimension().identifier().toString();
        double playerX = minecraft.player.getX();
        double playerZ = minecraft.player.getZ();
        float animationTime = (float) minecraft.level.getGameTime();
        List<BeamState> visible = new ArrayList<>();

        for (Waypoint waypoint : WaypointManager.getWaypoints()) {
            if (!waypoint.getDimension().equals(dimension)) continue;

            double dx = waypoint.getX() + 0.5 - playerX;
            double dz = waypoint.getZ() + 0.5 - playerZ;
            if (dx * dx + dz * dz > MAX_DISTANCE_SQUARED) continue;

            int height = Math.max(1, minecraft.level.getMaxY() - waypoint.getY());
            visible.add(new BeamState(
                waypoint.getX(), waypoint.getY(), waypoint.getZ(),
                height, waypoint.getColor() & 0xFFFFFF, animationTime
            ));
        }

        preparedBeams = List.copyOf(visible);
    }

    private static void render(LevelRenderContext context) {
        List<BeamState> beams = extractedBeams;
        if (beams.isEmpty()) return;

        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        var submitNodeCollector = context.submitNodeCollector();

        for (BeamState beam : beams) {
            poseStack.pushPose();
            poseStack.translate(beam.x - camera.x, beam.y - camera.y, beam.z - camera.z);
            BeaconRenderer.submitBeaconBeam(
                poseStack,
                submitNodeCollector,
                BeaconRenderer.BEAM_LOCATION,
                1.0f,
                beam.animationTime,
                0,
                beam.height,
                beam.color,
                BeaconRenderer.SOLID_BEAM_RADIUS,
                BeaconRenderer.BEAM_GLOW_RADIUS
            );
            poseStack.popPose();
        }
    }

    private record BeamState(int x, int y, int z, int height, int color, float animationTime) {}
}
