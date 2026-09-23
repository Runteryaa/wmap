package com.runterya.worldmap.gui;

import com.runterya.worldmap.client.ClientMapManager;
import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.Map;
import java.util.UUID;

public class WorldMapScreen extends Screen {
    private static final Identifier PLAYER_MARKER = Identifier.fromNamespaceAndPath("minecraft", "textures/map/decorations/player.png");

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

        // Render player icons
        if (Minecraft.getInstance().player != null) {
            double pX = Minecraft.getInstance().player.getX();
            double pZ = Minecraft.getInstance().player.getZ();
            // Minecraft yaw: 0=south, 90=west, 180=north, -90=east
            // On our map: south=+Z (down), north=-Z (up)
            // Arrow sprite points "up" by default (north), so add 180 to flip to south for yaw=0
            float yaw = Minecraft.getInstance().player.getYRot();
            
            graphics.pose().pushMatrix();
            graphics.pose().translate((float)pX, (float)pZ);
            graphics.pose().rotate((float) Math.toRadians(yaw + 180.0));
            // id, x0, y0, x1, y1, u0, u1, v0, v1
            graphics.blit(PLAYER_MARKER, -4, -4, 4, 4, 0.0f, 1.0f, 0.0f, 1.0f);
            graphics.pose().popMatrix();
        }

        for (com.runterya.worldmap.network.PlayerPosPayload.PlayerPos p : ClientMapManager.getOtherPlayers()) {
            if (Minecraft.getInstance().player != null) {
                boolean sameUuid = p.uuid().equals(Minecraft.getInstance().player.getUUID());
                boolean sameName = p.name().equals(Minecraft.getInstance().player.getName().getString());
                if (sameUuid || sameName) {
                    continue;
                }
            }
            graphics.pose().pushMatrix();
            graphics.pose().translate((float)p.x(), (float)p.z());
            graphics.blit(PLAYER_MARKER, -4, -4, 4, 4, 0.0f, 1.0f, 0.0f, 1.0f);
            graphics.pose().popMatrix();
        }

        graphics.pose().popMatrix();

        // --- SCREEN SPACE RENDERING ---
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String currentDim = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.dimension().identifier().toString() : "unknown";
        
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
