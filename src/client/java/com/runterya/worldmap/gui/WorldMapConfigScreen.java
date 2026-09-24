package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorldMapConfigScreen extends Screen {
    private final Screen parent;

    public WorldMapConfigScreen(Screen parent) {
        super(Component.literal("WorldMap Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addRenderableWidget(Button.builder(toggleLabel(), button -> {
            WorldMapConfig.setOpenWaypointActionsOnLook(!WorldMapConfig.openWaypointActionsOnLook());
            button.setMessage(toggleLabel());
        }).bounds(centerX - 150, centerY - 10, 300, 20).build());

        this.addRenderableWidget(Button.builder(layerLabel(), button -> {
            WorldMapConfig.cycleMapLayer();
            button.setMessage(layerLabel());
        }).bounds(centerX - 150, centerY + 16, 300, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(centerX - 100, centerY + 48, 200, 20).build());
    }

    private static Component toggleLabel() {
        return Component.literal("B while looking at a waypoint: "
            + (WorldMapConfig.openWaypointActionsOnLook() ? "Waypoint actions" : "Add waypoint"));
    }

    private static Component layerLabel() {
        String label = switch (WorldMapConfig.mapLayer()) {
            case MY_EXPLORED -> "My explored areas";
            case OTHERS_EXPLORED -> "Others' explored areas";
            case ALL -> "All explored areas";
        };
        return Component.literal("Shared map layer: " + label);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
