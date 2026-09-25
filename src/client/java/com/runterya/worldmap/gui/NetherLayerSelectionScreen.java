package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import com.runterya.worldmap.backend.NetherMapView;
import com.runterya.worldmap.client.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Lets the player choose the surface view or a saved vertical cave layer. */
public final class NetherLayerSelectionScreen extends Screen {
    private static final int COLUMNS = 5;
    private static final int CELL_HEIGHT = 24;
    private final Screen parent;
    private final int minY;
    private final int maxY;
    private final String selectedDimension;

    public NetherLayerSelectionScreen(Screen parent, int minY, int maxY, String selectedDimension) {
        super(Component.literal("Cave Map Layer"));
        this.parent = parent;
        this.minY = minY;
        this.maxY = maxY;
        this.selectedDimension = selectedDimension;
    }

    @Override
    protected void init() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            ClientPlatform.setScreen(minecraft, this.parent);
            return;
        }

        int minLayerY = NetherMapView.getPlayerLayerY(
            this.minY, this.minY, this.maxY);
        int maxLayerY = NetherMapView.getPlayerLayerY(
            this.maxY - 1, this.minY, this.maxY);
        int layerCount = (maxLayerY - minLayerY) / NetherMapView.CAVE_LAYER_STEP + 1;
        int itemCount = layerCount + 2;
        int rows = (itemCount + COLUMNS - 1) / COLUMNS;

        int panelWidth = Math.min(470, this.width - 24);
        int cellGap = 4;
        int cellWidth = (panelWidth - 32 - (COLUMNS - 1) * cellGap) / COLUMNS;
        int panelHeight = 64 + rows * CELL_HEIGHT + 34;
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = Math.max(8, (this.height - panelHeight) / 2);
        int gridLeft = panelLeft + (panelWidth - (COLUMNS * cellWidth + (COLUMNS - 1) * cellGap)) / 2;
        int gridTop = panelTop + 48;

        boolean bedrockSelected = WorldMapConfig.netherMapView() == NetherMapView.BEDROCK_SURFACE;
        this.addRenderableWidget(Button.builder(Component.literal(bedrockSelected ? "✓ Bedrock top" : "Bedrock top"), button -> {
            WorldMapConfig.selectNetherBedrockTop();
            ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(gridLeft, gridTop, cellWidth, 20).build());

        boolean autoSelected = WorldMapConfig.netherMapView() == NetherMapView.CAVE_LAYER
            && WorldMapConfig.isNetherLayerAuto();
        this.addRenderableWidget(Button.builder(Component.literal(autoSelected ? "✓ Auto" : "Auto"), button -> {
            WorldMapConfig.selectNetherAutoLayer();
            ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(gridLeft + cellWidth + cellGap, gridTop, cellWidth, 20).build());

        int selectedLayerY = WorldMapConfig.selectedNetherLayerY(
            this.minY, this.maxY, minecraft.level != null
                && minecraft.level.dimension().identifier().toString().equals(this.selectedDimension)
                ? minecraft.player.blockPosition().getY() : 40);
        for (int layerY = minLayerY; layerY <= maxLayerY; layerY += NetherMapView.CAVE_LAYER_STEP) {
            int index = 2 + (layerY - minLayerY) / NetherMapView.CAVE_LAYER_STEP;
            int column = index % COLUMNS;
            int row = index / COLUMNS;
            String label = (WorldMapConfig.netherMapView() == NetherMapView.CAVE_LAYER
                && !WorldMapConfig.isNetherLayerAuto() && layerY == selectedLayerY
                ? "✓ Y " : "Y ") + layerY;
            int selectedY = layerY;
            this.addRenderableWidget(Button.builder(Component.literal(label), button -> {
                WorldMapConfig.selectNetherCaveLayer(selectedY);
                ClientPlatform.setScreen(this.minecraft, this.parent);
            }).bounds(gridLeft + column * (cellWidth + cellGap), gridTop + row * CELL_HEIGHT,
                cellWidth, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button ->
            ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(this.width / 2 - 80, panelTop + panelHeight - 28, 160, 20).build());
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(470, this.width - 24);
        int minLayerY = NetherMapView.getPlayerLayerY(
            this.minY, this.minY, this.maxY);
        int maxLayerY = NetherMapView.getPlayerLayerY(
            this.maxY - 1, this.minY, this.maxY);
        int layerCount = (maxLayerY - minLayerY) / NetherMapView.CAVE_LAYER_STEP + 1;
        int rows = (layerCount + 2 + COLUMNS - 1) / COLUMNS;
        int panelHeight = 64 + rows * CELL_HEIGHT + 34;
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = Math.max(8, (this.height - panelHeight) / 2);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xE0181A20);
        graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF777777);
        graphics.centeredText(this.font, "Cave Map Layer", this.width / 2, panelTop + 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, "Choose bedrock top or any 8-block cave layer",
            this.width / 2, panelTop + 29, 0xFFB8B8B8);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
