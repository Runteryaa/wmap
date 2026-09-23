package com.runterya.worldmap.gui;

import com.runterya.worldmap.client.ClientMapManager;
import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.Map;
import java.util.UUID;

public class WorldMapScreen extends Screen {
    private double panX = 0;
    private double panY = 0;
    private double scale = 1.0;

    public WorldMapScreen() {
        super(Component.literal("World Map"));
        
        // Center the map on the local player if they exist
        if (Minecraft.getInstance().player != null) {
            this.panX = -Minecraft.getInstance().player.getX();
            this.panY = -Minecraft.getInstance().player.getZ();
        }
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        graphics.pose().pushMatrix();
        
        // Apply panning and scaling
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale((float) scale, (float) scale);
        graphics.pose().translate((float) panX, (float) panY);

        // Render map regions
        Map<ChunkPos, ClientMapManager.RegionTexture> regions = ClientMapManager.getRegions();
        for (Map.Entry<ChunkPos, ClientMapManager.RegionTexture> entry : regions.entrySet()) {
            ChunkPos regionPos = entry.getKey();
            ClientMapManager.RegionTexture texture = entry.getValue();

            int worldX = regionPos.x() * 512;
            int worldZ = regionPos.z() * 512;

            if (texture.getTextureLocation() != null) {
                // Draw 512x512 region texture (id, x0, y0, x1, y1, u0, u1, v0, v1)
                graphics.blit(texture.getTextureLocation(), worldX, worldZ, worldX + 512, worldZ + 512, 0.0f, 1.0f, 0.0f, 1.0f);
            }
        }

        // Render waypoints (will be drawn in screen space later to prevent scaling)

        graphics.pose().popMatrix();

        // --- SCREEN SPACE RENDERING ---
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String currentDim = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.dimension().identifier().toString() : "unknown";

        // Player arrows stay screen-sized while zooming, like waypoint markers.
        if (Minecraft.getInstance().player != null) {
            var player = Minecraft.getInstance().player;
            double playerX = centerX + (player.getX() + panX) * scale;
            double playerY = centerY + (player.getZ() + panY) * scale;
            double[] marker = markerScreenPosition(playerX, playerY, centerX, centerY);
            boolean onScreen = playerX >= 10 && playerX <= width - 10
                && playerY >= 10 && playerY <= height - 24;
            float rotation = onScreen
                ? player.getYRot() + 180.0f
                : (float) Math.toDegrees(Math.atan2(playerX - centerX, -(playerY - centerY)));
            drawPlayerArrow(graphics, marker[0], marker[1], rotation, 0xFFFFFFFF);
        }

        for (com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player : ClientMapManager.getOtherPlayers()) {
            if (Minecraft.getInstance().player != null && player.uuid().equals(Minecraft.getInstance().player.getUUID())) {
                continue;
            }
            drawOtherPlayer(graphics, font, player, centerX, centerY);
        }
        
        // Render waypoints in screen space
        for (com.runterya.worldmap.client.waypoint.Waypoint wp : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
            if (!wp.getDimension().equals(currentDim)) continue;
            
            double screenX = centerX + (wp.getX() + panX) * scale;
            double screenY = centerY + (wp.getZ() + panY) * scale;
            
            if (screenX < -50 || screenX > this.width + 50 || screenY < -50 || screenY > this.height + 50) continue;
            
            int sx = (int) Math.round(screenX);
            int sy = (int) Math.round(screenY);
            
            // Xaero style waypoint marker (outlined box)
            graphics.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFF000000);
            graphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, wp.getColor() | 0xFF000000);
            
            // Draw name centered above if hovered
            if (mouseX >= sx - 4 && mouseX <= sx + 4 && mouseY >= sy - 4 && mouseY <= sy + 4) {
                String name = wp.getName();
                graphics.centeredText(font, name, sx, sy - 14, wp.getColor() | 0xFF000000);
            }
        }

        // Draw mouse coordinates
        double mouseWorldX = (mouseX - centerX) / scale - panX;
        double mouseWorldZ = (mouseY - centerY) / scale - panY;
        String coordText = String.format("X: %d, Z: %d", (int) Math.round(mouseWorldX), (int) Math.round(mouseWorldZ));
        graphics.text(font, coordText, 5, 5, 0xFFFFFF, true);
    }

    private void drawOtherPlayer(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                 net.minecraft.client.gui.Font font,
                                 com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player,
                                 int centerX, int centerY) {
        double screenX = centerX + (player.x() + panX) * scale;
        double screenY = centerY + (player.z() + panY) * scale;
        double dx = screenX - centerX;
        double dy = screenY - centerY;
        int color = colorForPlayer(player.uuid());
        boolean onScreen = screenX >= 10 && screenX <= width - 10
            && screenY >= 10 && screenY <= height - 24;
        double[] markerPosition = playerMarkerPosition(player, centerX, centerY);

        if (onScreen) {
            drawPlayerArrow(graphics, markerPosition[0], markerPosition[1], player.yaw() + 180.0f, color);
        } else {
            float towardPlayer = (float) Math.toDegrees(Math.atan2(dx, -dy));
            drawPlayerArrow(graphics, markerPosition[0], markerPosition[1], towardPlayer, color);
        }

        drawPlayerName(graphics, font, player.name(), markerPosition[0], markerPosition[1]);
    }

    private double[] playerMarkerPosition(
        com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player, int centerX, int centerY
    ) {
        double screenX = centerX + (player.x() + panX) * scale;
        double screenY = centerY + (player.z() + panY) * scale;
        return markerScreenPosition(screenX, screenY, centerX, centerY);
    }

    private double[] markerScreenPosition(double screenX, double screenY, int centerX, int centerY) {
        if (screenX >= 10 && screenX <= width - 10 && screenY >= 10 && screenY <= height - 24) {
            return new double[] {screenX, screenY};
        }

        double dx = screenX - centerX;
        double dy = screenY - centerY;
        double halfWidth = Math.max(1, width / 2.0 - 16);
        double halfHeight = Math.max(1, height / 2.0 - 28);
        double scaleToEdge = Math.min(
            dx == 0 ? Double.POSITIVE_INFINITY : halfWidth / Math.abs(dx),
            dy == 0 ? Double.POSITIVE_INFINITY : halfHeight / Math.abs(dy)
        );
        return new double[] {centerX + dx * scaleToEdge, centerY + dy * scaleToEdge};
    }

    private void drawPlayerArrow(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                 double screenX, double screenY, float rotationDegrees, int color) {
        if (screenX < -10 || screenX > width + 10 || screenY < -10 || screenY > height + 10) return;

        graphics.pose().pushMatrix();
        graphics.pose().translate((float) screenX, (float) screenY);
        // The arrow points north before rotation; Minecraft yaw 0 faces south.
        graphics.pose().rotate((float) Math.toRadians(rotationDegrees));

        // Pixel-style arrow with a dark outline, drawn at a fixed screen size.
        graphics.fill(-1, -6, 1, -2, 0xFF000000);
        graphics.fill(-3, -3, 3, -1, 0xFF000000);
        graphics.fill(-4, -1, 4, 1, 0xFF000000);
        graphics.fill(-2, 1, 2, 6, 0xFF000000);
        graphics.fill(-1, -5, 1, -2, color);
        graphics.fill(-2, -3, 2, -1, color);
        graphics.fill(-3, -1, 3, 1, color);
        graphics.fill(-1, 1, 1, 5, color);
        graphics.pose().popMatrix();
    }

    private void drawPlayerName(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                net.minecraft.client.gui.Font font, String name,
                                double markerX, double markerY) {
        int textWidth = font.width(name);
        int labelX = (int) Math.round(Math.max(textWidth / 2.0 + 2,
            Math.min(width - textWidth / 2.0 - 2, markerX)));
        int labelY = (int) Math.round(Math.min(height - font.lineHeight - 2, markerY + 10));
        graphics.fill(labelX - textWidth / 2 - 2, labelY - 1,
            labelX + textWidth / 2 + 2, labelY + font.lineHeight, 0x99000000);
        graphics.centeredText(font, name, labelX, labelY, 0xFFFFFFFF);
    }

    private static int colorForPlayer(UUID uuid) {
        long mixed = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 23);
        float hue = (float) ((mixed >>> 40 & 0xFFFFFFL) / 16777216.0);
        return java.awt.Color.HSBtoRGB(hue, 0.82f, 1.0f) | 0xFF000000;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == 0) { // Left click drag
            // Adjust pan based on drag and scale
            this.panX += dragX / this.scale;
            this.panY += dragY / this.scale;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDouble) {
        if (event.button() == 0) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            if (Minecraft.getInstance().player != null) {
                var localPlayer = Minecraft.getInstance().player;
                double localX = centerX + (localPlayer.getX() + panX) * scale;
                double localY = centerY + (localPlayer.getZ() + panY) * scale;
                double[] localMarker = markerScreenPosition(localX, localY, centerX, centerY);
                if (isNearMarker(event.x(), event.y(), localMarker[0], localMarker[1])) {
                    centerMapOn(localPlayer.getX(), localPlayer.getZ());
                    return true;
                }
            }

            for (com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player : ClientMapManager.getOtherPlayers()) {
                if (Minecraft.getInstance().player != null
                    && player.uuid().equals(Minecraft.getInstance().player.getUUID())) continue;
                double[] marker = playerMarkerPosition(player, centerX, centerY);
                if (isNearMarker(event.x(), event.y(), marker[0], marker[1])) {
                    centerMapOn(player.x(), player.z());
                    return true;
                }
            }
        }

        if (event.button() == 1) { // Right click
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            
            // Convert screen coordinates to world coordinates
            double worldX = (event.x() - centerX) / this.scale - this.panX;
            double worldZ = (event.y() - centerY) / this.scale - this.panY;
            
            int color = 0xFF000000 | new java.util.Random().nextInt(0xFFFFFF);
            String dim = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.dimension().identifier().toString() : "unknown";
            
            // We use integer block coordinates, Y is estimated or set to 64
            int blockX = (int) Math.round(worldX);
            int blockZ = (int) Math.round(worldZ);
            
            // Check if we clicked on an existing waypoint
            // The waypoint is drawn as a 6x6 block square in world space
            // Let's add a bit of leniency based on scale so it's easier to click
            double clickTolerance = Math.max(4.0, 5.0 / this.scale);
            
            com.runterya.worldmap.client.waypoint.Waypoint clickedWaypoint = null;
            for (com.runterya.worldmap.client.waypoint.Waypoint wp : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
                if (!wp.getDimension().equals(dim)) continue;
                if (Math.abs(wp.getX() - worldX) <= clickTolerance && Math.abs(wp.getZ() - worldZ) <= clickTolerance) {
                    clickedWaypoint = wp;
                    break;
                }
            }
            
            if (clickedWaypoint != null) {
                com.runterya.worldmap.client.ClientPlatform.setScreen(Minecraft.getInstance(), new WaypointContextMenuScreen(this, clickedWaypoint));
            } else {
                int blockY = 64; // Default Y
                if (Minecraft.getInstance().player != null) {
                    blockY = Minecraft.getInstance().player.getBlockY();
                }
                
                com.runterya.worldmap.client.ClientPlatform.setScreen(Minecraft.getInstance(), new WaypointAddScreen(this, blockX, blockY, blockZ, dim));
            }
            return true;
        }
        return super.mouseClicked(event, isDouble);
    }

    private static boolean isNearMarker(double mouseX, double mouseY, double markerX, double markerY) {
        double dx = mouseX - markerX;
        double dy = mouseY - markerY;
        return dx * dx + dy * dy <= 100;
    }

    private void centerMapOn(double worldX, double worldZ) {
        panX = -worldX;
        panY = -worldZ;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) {
            scale *= 1.2; // Zoom in
        } else if (scrollY < 0) {
            scale /= 1.2; // Zoom out
        }
        scale = Math.max(0.1, Math.min(scale, 10.0)); // Clamp scale
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause game in singleplayer while map is open
    }
}
