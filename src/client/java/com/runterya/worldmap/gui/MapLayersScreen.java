package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Visibility and ownership filters for the world map's visual layers. */
public final class MapLayersScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int PANEL_HEIGHT = 252;
    private final Screen parent;

    public MapLayersScreen(Screen parent) {
        super(Component.literal("Map Layers"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, this.width - 24);
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = Math.max(12, (this.height - PANEL_HEIGHT) / 2);
        int buttonLeft = panelLeft + 18;
        int buttonWidth = panelWidth - 36;

        this.addRenderableWidget(Button.builder(playersLabel(), button -> {
            WorldMapConfig.toggleShowPlayers();
            button.setMessage(playersLabel());
        }).bounds(buttonLeft, panelTop + 98, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(waypointsLabel(), button -> {
            WorldMapConfig.toggleShowWaypoints();
            button.setMessage(waypointsLabel());
        }).bounds(buttonLeft, panelTop + 132, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(this.width / 2 - 100, panelTop + 200, 200, 22).build());
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(PANEL_WIDTH, this.width - 24);
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = Math.max(12, (this.height - PANEL_HEIGHT) / 2);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + PANEL_HEIGHT, 0xE0181A20);
        graphics.outline(panelLeft, panelTop, panelWidth, PANEL_HEIGHT, 0xFF777777);
        graphics.centeredText(this.font, "Map Layers", this.width / 2, panelTop + 12, 0xFFFFFFFF);
        graphics.centeredText(this.font, "Choose which markers appear on your map",
            this.width / 2, panelTop + 31, 0xFFB8B8B8);
        graphics.fill(panelLeft + 16, panelTop + 52, panelLeft + panelWidth - 16, panelTop + 53, 0xFF55555F);
        graphics.text(this.font, "Visibility", panelLeft + 18, panelTop + 63, 0xFFFFFFFF, true);
        graphics.text(this.font, "Toggle players and waypoints independently.",
            panelLeft + 18, panelTop + 78, 0xFFB8B8B8, true);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static Component playersLabel() {
        return visibilityLabel("Players", WorldMapConfig.showPlayers());
    }

    private static Component waypointsLabel() {
        return visibilityLabel("Waypoints", WorldMapConfig.showWaypoints());
    }

    private static Component visibilityLabel(String name, boolean visible) {
        return Component.literal(name + ": " + (visible ? "Shown" : "Hidden"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
