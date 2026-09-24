package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Visibility and ownership filters for the world map's visual layers. */
public final class MapLayersScreen extends Screen {
    private final Screen parent;

    public MapLayersScreen(Screen parent) {
        super(Component.literal("Map Layers"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addRenderableWidget(Button.builder(playersLabel(), button -> {
            WorldMapConfig.toggleShowPlayers();
            button.setMessage(playersLabel());
        }).bounds(centerX - 150, centerY - 28, 300, 20).build());

        this.addRenderableWidget(Button.builder(waypointsLabel(), button -> {
            WorldMapConfig.toggleShowWaypoints();
            button.setMessage(waypointsLabel());
        }).bounds(centerX - 150, centerY + 2, 300, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(centerX - 100, centerY + 34, 200, 20).build());
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
