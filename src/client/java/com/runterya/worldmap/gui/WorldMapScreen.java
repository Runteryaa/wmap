package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import com.runterya.worldmap.client.ClientMapManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Locale;

public class WorldMapScreen extends Screen {
    private static final double MIN_SCALE = 0.025;
    private static final double MAX_SCALE = 10.0;
    private static final net.minecraft.resources.Identifier PLAYER_MARKER =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "textures/map/decorations/player.png");

    private double panX = 0;
    private double panY = 0;
    private double scale = 1.0;
    private EditBox waypointSearchField;
    private int searchScrollOffset;
    private static final int SEARCH_PANEL_WIDTH = 220;
    private static final int SEARCH_ROW_HEIGHT = 22;
    private static final int MAX_SEARCH_RESULTS = 8;

    public WorldMapScreen() {
        super(Component.literal("World Map"));
        
        // Center the map on the local player if they exist
        if (Minecraft.getInstance().player != null) {
            this.panX = -Minecraft.getInstance().player.getX();
            this.panY = -Minecraft.getInstance().player.getZ();
        }
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Map settings"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(
                this.minecraft, new WorldMapConfigScreen(this)
            )
        ).bounds(8, this.height - 28, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Layers"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(
                this.minecraft, new MapLayersScreen(this)
            )
        ).bounds(114, this.height - 28, 80, 20).build());

        this.waypointSearchField = new EditBox(this.font,
            Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8), 8,
            Math.min(SEARCH_PANEL_WIDTH, this.width - 16), 20,
            Component.literal("Search waypoints"));
        this.waypointSearchField.setHint(Component.literal("Search waypoint or item"));
        this.waypointSearchField.setMaxLength(64);
        this.waypointSearchField.setResponder(text -> this.searchScrollOffset = 0);
        this.addRenderableWidget(this.waypointSearchField);
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        String currentDim = Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.dimension().identifier().toString()
            : "minecraft:overworld";

        graphics.pose().pushMatrix();
        
        // Apply panning and scaling
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale((float) scale, (float) scale);
        graphics.pose().translate((float) panX, (float) panY);

        // Render map regions
        if (WorldMapConfig.showExploredAreas()) {
            Map<ChunkPos, ClientMapManager.RegionTexture> regions = ClientMapManager.getRegions(currentDim);
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
        }

        // Render waypoints (will be drawn in screen space later to prevent scaling)

        graphics.pose().popMatrix();

        // --- SCREEN SPACE RENDERING ---
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        // Render waypoints in screen space
        if (WorldMapConfig.showWaypoints()) {
            for (com.runterya.worldmap.client.waypoint.Waypoint wp : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
                if (!wp.getDimension().equals(currentDim)) continue;

                double screenX = centerX + (wp.getX() + panX) * scale;
                double screenY = centerY + (wp.getZ() + panY) * scale;

                boolean onScreen = isMapPositionVisible(screenX, screenY);
                if (!onScreen) {
                    double[] marker = markerScreenPosition(screenX, screenY, centerX, centerY);
                    drawWaypointMarker(graphics, wp, marker[0], marker[1]);
                    continue;
                }

                int sx = (int) Math.round(screenX);
                int sy = (int) Math.round(screenY);
                drawWaypointMarker(graphics, wp, sx, sy);

                // Draw name centered above if hovered
                if (mouseX >= sx - 7 && mouseX <= sx + 7 && mouseY >= sy - 7 && mouseY <= sy + 7) {
                    String name = wp.getName();
                    graphics.centeredText(font, name, sx, sy - 14, wp.getColor() | 0xFF000000);
                }
            }
        }

        // Draw the vanilla player indicators after waypoints so they stay on top.
        if (WorldMapConfig.showPlayers()) {
            drawPlayerMarkers(graphics, font, centerX, centerY, currentDim);
        }

        // Draw mouse coordinates
        double mouseWorldX = (mouseX - centerX) / scale - panX;
        double mouseWorldZ = (mouseY - centerY) / scale - panY;
        String coordText = String.format("X: %d, Z: %d", (int) Math.round(mouseWorldX), (int) Math.round(mouseWorldZ));
        graphics.fill(3, 3, font.width(coordText) + 8, font.lineHeight + 7, 0x99000000);
        graphics.text(font, coordText, 5, 5, 0xFFFFFFFF, true);

        drawWaypointSearchResults(graphics, mouseX, mouseY, currentDim);
    }

    private List<com.runterya.worldmap.client.waypoint.Waypoint> getWaypointSearchResults(String currentDim) {
        if (this.waypointSearchField == null || this.waypointSearchField.getValue().isBlank()) return List.of();
        String query = this.waypointSearchField.getValue().trim().toLowerCase(Locale.ROOT);
        return com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints().stream()
            .filter(wp -> wp.getDimension().equals(currentDim))
            .filter(wp -> waypointMatchesSearch(wp, query))
            .toList();
    }

    private static boolean waypointMatchesSearch(
        com.runterya.worldmap.client.waypoint.Waypoint waypoint, String query
    ) {
        if (waypoint.getName().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (waypoint.getIcon().isBlank()) return false;
        String iconId = waypoint.getIcon().toLowerCase(Locale.ROOT);
        if (iconId.contains(query)) return true;
        var iconIdentifier = net.minecraft.resources.Identifier.tryParse(waypoint.getIcon());
        if (iconIdentifier == null) return false;
        var item = BuiltInRegistries.ITEM.getValue(iconIdentifier);
        return item != Items.AIR
            && new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void drawWaypointSearchResults(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                           int mouseX, int mouseY, String currentDim) {
        List<com.runterya.worldmap.client.waypoint.Waypoint> results = getWaypointSearchResults(currentDim);
        if (results.isEmpty()) return;

        int panelX = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
        int panelY = 31;
        int visibleRows = Math.min(MAX_SEARCH_RESULTS, Math.max(1, (this.height - panelY - 36) / SEARCH_ROW_HEIGHT));
        int maxOffset = Math.max(0, results.size() - visibleRows);
        this.searchScrollOffset = Math.max(0, Math.min(maxOffset, this.searchScrollOffset));
        int shownRows = Math.min(visibleRows, results.size() - this.searchScrollOffset);
        int panelHeight = shownRows * SEARCH_ROW_HEIGHT + 2;
        graphics.fill(panelX, panelY, panelX + SEARCH_PANEL_WIDTH, panelY + panelHeight, 0xF0202020);
        graphics.outline(panelX, panelY, SEARCH_PANEL_WIDTH, panelHeight, 0xFF777777);

        for (int row = 0; row < shownRows; row++) {
            int index = this.searchScrollOffset + row;
            var waypoint = results.get(index);
            int rowY = panelY + 1 + row * SEARCH_ROW_HEIGHT;
            boolean hovered = mouseX >= panelX && mouseX < panelX + SEARCH_PANEL_WIDTH
                && mouseY >= rowY && mouseY < rowY + SEARCH_ROW_HEIGHT;
            if (hovered) graphics.fill(panelX + 1, rowY, panelX + SEARCH_PANEL_WIDTH - 1,
                rowY + SEARCH_ROW_HEIGHT, 0xFF45454F);

            if (waypoint.getIcon().isBlank()) {
                graphics.fill(panelX + 5, rowY + 5, panelX + 19, rowY + 19, 0xFF000000);
                graphics.fill(panelX + 6, rowY + 6, panelX + 18, rowY + 18,
                    waypoint.getColor() | 0xFF000000);
            } else {
                var iconId = net.minecraft.resources.Identifier.tryParse(waypoint.getIcon());
                var item = iconId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(iconId);
                if (item == Items.AIR) {
                    graphics.fill(panelX + 5, rowY + 5, panelX + 19, rowY + 19,
                        waypoint.getColor() | 0xFF000000);
                } else {
                    graphics.item(new ItemStack(item), panelX + 4, rowY + 3);
                }
            }

            String label = waypoint.getName() + "  " + waypoint.getX() + ", " + waypoint.getZ();
            int maxTextWidth = SEARCH_PANEL_WIDTH - 30;
            while (!label.isEmpty() && this.font.width(label) > maxTextWidth) {
                label = label.substring(0, label.length() - 1);
            }
            graphics.text(this.font, label, panelX + 24, rowY + 6, 0xFFFFFFFF, true);
        }
    }

    private void drawPlayerMarkers(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   net.minecraft.client.gui.Font font, int centerX, int centerY, String currentDim) {
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
            if (!player.dimension().equals(currentDim)) continue;
            if (Minecraft.getInstance().player != null && player.uuid().equals(Minecraft.getInstance().player.getUUID())) {
                continue;
            }
            drawOtherPlayer(graphics, font, player, centerX, centerY);
        }
    }

    private void drawWaypointMarker(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                    com.runterya.worldmap.client.waypoint.Waypoint wp,
                                    double screenX, double screenY) {
        int sx = (int) Math.round(screenX);
        int sy = (int) Math.round(screenY);
        if (wp.getIcon().isBlank()) {
            // Keep the compact colored square as the default marker.
            graphics.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFF000000);
            graphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, wp.getColor() | 0xFF000000);
        } else {
            graphics.fill(sx - 5, sy - 5, sx + 5, sy + 5, 0xFF000000);
            graphics.fill(sx - 4, sy - 4, sx + 4, sy + 4, wp.getColor() | 0xFF000000);
            var iconId = net.minecraft.resources.Identifier.tryParse(wp.getIcon());
            var item = iconId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(iconId);
            if (item == Items.AIR) {
                graphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFFFFF);
            } else {
                graphics.pose().pushMatrix();
                graphics.pose().translate(sx - 4, sy - 4);
                graphics.pose().scale(0.5f, 0.5f);
                graphics.item(new ItemStack(item), 0, 0);
                graphics.pose().popMatrix();
            }
        }
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

        // Vanilla map player-decoration texture, kept screen-sized and tinted per player.
        graphics.blit(RenderPipelines.GUI_TEXTURED, PLAYER_MARKER,
            -7, -7, 0.0f, 0.0f, 14, 14, 8, 8, 8, 8, color);
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
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            // Adjust pan based on drag and scale
            this.panX += dragX / this.scale;
            this.panY += dragY / this.scale;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDouble) {
        // Let GUI controls (such as the Settings button) handle their clicks first.
        if (super.mouseClicked(event, isDouble)) return true;

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            String currentDim = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.dimension().identifier().toString()
                : "minecraft:overworld";

            if (this.waypointSearchField != null && !this.waypointSearchField.getValue().isBlank()) {
                List<com.runterya.worldmap.client.waypoint.Waypoint> results = getWaypointSearchResults(currentDim);
                int panelX = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
                int panelY = 31;
                int visibleRows = Math.min(MAX_SEARCH_RESULTS, Math.max(1, (this.height - panelY - 36) / SEARCH_ROW_HEIGHT));
                int shownRows = Math.min(visibleRows, Math.max(0, results.size() - this.searchScrollOffset));
                if (event.x() >= panelX && event.x() < panelX + SEARCH_PANEL_WIDTH
                    && event.y() >= panelY && event.y() < panelY + shownRows * SEARCH_ROW_HEIGHT + 2) {
                    int row = (int) (event.y() - panelY - 1) / SEARCH_ROW_HEIGHT;
                    int selectedIndex = this.searchScrollOffset + row;
                    if (selectedIndex >= 0 && selectedIndex < results.size()) {
                        var waypoint = results.get(selectedIndex);
                        centerMapOn(waypoint.getX(), waypoint.getZ());
                    }
                    return true;
                }
            }

            if (WorldMapConfig.showPlayers() && Minecraft.getInstance().player != null) {
                var localPlayer = Minecraft.getInstance().player;
                double localX = centerX + (localPlayer.getX() + panX) * scale;
                double localY = centerY + (localPlayer.getZ() + panY) * scale;
                double[] localMarker = markerScreenPosition(localX, localY, centerX, centerY);
                if (isNearMarker(event.x(), event.y(), localMarker[0], localMarker[1])) {
                    centerMapOn(localPlayer.getX(), localPlayer.getZ());
                    return true;
                }
            }

            if (WorldMapConfig.showPlayers()) {
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

            if (WorldMapConfig.showWaypoints()) {
                String dim = Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.dimension().identifier().toString()
                    : "minecraft:overworld";
                for (com.runterya.worldmap.client.waypoint.Waypoint wp
                    : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
                    if (!wp.getDimension().equals(dim)) continue;
                    double screenX = centerX + (wp.getX() + panX) * scale;
                    double screenY = centerY + (wp.getZ() + panY) * scale;
                    if (isMapPositionVisible(screenX, screenY)) continue;

                    double[] marker = markerScreenPosition(screenX, screenY, centerX, centerY);
                    if (isNearMarker(event.x(), event.y(), marker[0], marker[1])) {
                        centerMapOn(wp.getX(), wp.getZ());
                        return true;
                    }
                }
            }

            // Consume the left press so Screen keeps it captured for mouseDragged.
            return true;
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
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
            
            // Hit-test waypoints in screen space so the target stays usable at every zoom level.
            com.runterya.worldmap.client.waypoint.Waypoint clickedWaypoint = null;
            if (WorldMapConfig.showWaypoints()) {
                for (com.runterya.worldmap.client.waypoint.Waypoint wp : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
                    if (!wp.getDimension().equals(dim)) continue;
                    double waypointX = centerX + (wp.getX() + panX) * scale;
                    double waypointY = centerY + (wp.getZ() + panY) * scale;
                    if (Math.abs(waypointX - event.x()) <= 7 && Math.abs(waypointY - event.y()) <= 7) {
                        clickedWaypoint = wp;
                        break;
                    }
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
        return false;
    }

    private static boolean isNearMarker(double mouseX, double mouseY, double markerX, double markerY) {
        double dx = mouseX - markerX;
        double dy = mouseY - markerY;
        return dx * dx + dy * dy <= 100;
    }

    private boolean isMapPositionVisible(double screenX, double screenY) {
        return screenX >= 10 && screenX <= width - 10
            && screenY >= 10 && screenY <= height - 24;
    }

    private void centerMapOn(double worldX, double worldZ) {
        panX = -worldX;
        panY = -worldZ;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        String currentDim = Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.dimension().identifier().toString()
            : "minecraft:overworld";
        List<com.runterya.worldmap.client.waypoint.Waypoint> searchResults = getWaypointSearchResults(currentDim);
        int searchPanelX = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
        int searchVisibleRows = Math.min(MAX_SEARCH_RESULTS,
            Math.max(1, (this.height - 31 - 36) / SEARCH_ROW_HEIGHT));
        int searchShownRows = Math.min(searchVisibleRows, searchResults.size());
        if (searchShownRows > 0 && mouseX >= searchPanelX && mouseX < searchPanelX + SEARCH_PANEL_WIDTH
            && mouseY >= 31 && mouseY < 33 + searchShownRows * SEARCH_ROW_HEIGHT) {
            int maxOffset = Math.max(0, searchResults.size() - searchVisibleRows);
            this.searchScrollOffset = Math.max(0, Math.min(maxOffset,
                this.searchScrollOffset - (int) Math.signum(scrollY)));
            return true;
        }

        if (scrollY > 0) {
            scale *= 1.2; // Zoom in
        } else if (scrollY < 0) {
            scale /= 1.2; // Zoom out
        }
        scale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause game in singleplayer while map is open
    }
}
