package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorldMapConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 292;
    private final Screen parent;

    public WorldMapConfigScreen(Screen parent) {
        super(Component.literal("WorldMap Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, this.width - 24);
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = Math.max(12, (this.height - PANEL_HEIGHT) / 2);
        int buttonLeft = panelLeft + 18;
        int buttonWidth = panelWidth - 36;

        this.addRenderableWidget(Button.builder(toggleLabel(), button -> {
            WorldMapConfig.setOpenWaypointActionsOnLook(!WorldMapConfig.openWaypointActionsOnLook());
            button.setMessage(toggleLabel());
        }).bounds(buttonLeft, panelTop + 94, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(exploredAreasLabel(), button -> {
            WorldMapConfig.toggleShowExploredAreas();
            button.setMessage(exploredAreasLabel());
        }).bounds(buttonLeft, panelTop + 164, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(explorationFilterLabel(), button -> {
            WorldMapConfig.cycleMapLayer();
            button.setMessage(explorationFilterLabel());
        }).bounds(buttonLeft, panelTop + 190, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(this.width / 2 - 100, panelTop + 252, 200, 22).build());
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(PANEL_WIDTH, this.width - 24);
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = Math.max(12, (this.height - PANEL_HEIGHT) / 2);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + PANEL_HEIGHT, 0xE0181A20);
        graphics.outline(panelLeft, panelTop, panelWidth, PANEL_HEIGHT, 0xFF777777);
        graphics.centeredText(this.font, "WorldMap Settings", this.width / 2, panelTop + 12, 0xFFFFFFFF);
        graphics.centeredText(this.font, "Customize map controls and shared exploration",
            this.width / 2, panelTop + 31, 0xFFB8B8B8);
        graphics.fill(panelLeft + 16, panelTop + 52, panelLeft + panelWidth - 16, panelTop + 53, 0xFF55555F);

        graphics.text(this.font, "Controls", panelLeft + 18, panelTop + 63, 0xFFFFFFFF, true);
        graphics.text(this.font, "Choose what B does while you look at a waypoint.",
            panelLeft + 18, panelTop + 78, 0xFFB8B8B8, true);
        graphics.text(this.font, "Shared map", panelLeft + 18, panelTop + 132, 0xFFFFFFFF, true);
        graphics.text(this.font, "Choose which discovered chunks appear on the shared map.",
            panelLeft + 18, panelTop + 147, 0xFFB8B8B8, true);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static Component toggleLabel() {
        return Component.literal("B while looking at a waypoint: "
            + (WorldMapConfig.openWaypointActionsOnLook() ? "Waypoint actions" : "Add waypoint"));
    }

    private static Component exploredAreasLabel() {
        return Component.literal("Explored map: " + (WorldMapConfig.showExploredAreas() ? "Shown" : "Hidden"));
    }

    private static Component explorationFilterLabel() {
        String label = switch (WorldMapConfig.mapLayer()) {
            case MY_EXPLORED -> "My explored areas";
            case OTHERS_EXPLORED -> "Others' explored areas";
            case ALL -> "All explored areas";
        };
        return Component.literal("Explored by: " + label);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
