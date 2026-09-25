package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import com.runterya.worldmap.client.ClientMapManager;
import com.runterya.worldmap.network.PlayerPosPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorldMapConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 376;
    private static final int PLAYER_PICKER_WIDTH = 280;
    private static final int PLAYER_PICKER_HEIGHT = 220;
    private static final int PLAYER_PICKER_ROW_HEIGHT = 22;
    private static final int PLAYER_PICKER_VISIBLE_ROWS = 6;
    private final Screen parent;
    private Button explorationFilterButton;
    private Button explorerSelectButton;
    private boolean playerPickerOpen;
    private int playerPickerScroll;

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

        this.explorationFilterButton = this.addRenderableWidget(Button.builder(explorationFilterLabel(), button -> {
            WorldMapConfig.cycleMapLayer();
            button.setMessage(explorationFilterLabel());
        }).bounds(buttonLeft, panelTop + 190, buttonWidth, 22).build());

        this.explorerSelectButton = this.addRenderableWidget(Button.builder(explorerSelectLabel(), button -> {
            this.playerPickerScroll = 0;
            this.playerPickerOpen = true;
        }).bounds(buttonLeft, panelTop + 216, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(playersLabel(), button -> {
            WorldMapConfig.toggleShowPlayers();
            button.setMessage(playersLabel());
        }).bounds(buttonLeft, panelTop + 286, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(waypointsLabel(), button -> {
            WorldMapConfig.toggleShowWaypoints();
            button.setMessage(waypointsLabel());
        }).bounds(buttonLeft, panelTop + 312, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(this.width / 2 - 100, panelTop + 344, 200, 22).build());
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
        graphics.text(this.font, "Layers", panelLeft + 18, panelTop + 252, 0xFFFFFFFF, true);
        graphics.text(this.font, "Choose which markers are visible on the map.",
            panelLeft + 18, panelTop + 267, 0xFFB8B8B8, true);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.playerPickerOpen) drawPlayerPicker(graphics, mouseX, mouseY);
    }

    private List<PlayerPosPayload.PlayerPos> availableMapPlayers() {
        Map<UUID, PlayerPosPayload.PlayerPos> players = new LinkedHashMap<>();
        for (PlayerPosPayload.PlayerPos player : ClientMapManager.getOtherPlayers()) {
            players.put(player.uuid(), player);
        }
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null && Minecraft.getInstance().level != null) {
            String dimension = Minecraft.getInstance().level.dimension().identifier().toString();
            players.put(localPlayer.getUUID(), new PlayerPosPayload.PlayerPos(
                localPlayer.getUUID(), localPlayer.getX(), localPlayer.getZ(), localPlayer.getYRot(),
                localPlayer.getName().getString(), dimension));
        }
        return players.values().stream()
            .sorted(Comparator.comparing(PlayerPosPayload.PlayerPos::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private void drawPlayerPicker(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = (this.width - PLAYER_PICKER_WIDTH) / 2;
        int top = Math.max(8, (this.height - PLAYER_PICKER_HEIGHT) / 2);
        graphics.fill(0, 0, this.width, this.height, 0x99000000);
        graphics.fill(left, top, left + PLAYER_PICKER_WIDTH, top + PLAYER_PICKER_HEIGHT, 0xFF202020);
        graphics.outline(left, top, PLAYER_PICKER_WIDTH, PLAYER_PICKER_HEIGHT, 0xFFAAAAAA);
        graphics.centeredText(this.font, "Choose map explorer", left + PLAYER_PICKER_WIDTH / 2, top + 10, 0xFFFFFFFF);

        List<PlayerPosPayload.PlayerPos> players = availableMapPlayers();
        int maxScroll = Math.max(0, players.size() - PLAYER_PICKER_VISIBLE_ROWS);
        this.playerPickerScroll = Math.max(0, Math.min(maxScroll, this.playerPickerScroll));
        int shown = Math.min(PLAYER_PICKER_VISIBLE_ROWS, players.size() - this.playerPickerScroll);
        for (int row = 0; row < shown; row++) {
            int index = this.playerPickerScroll + row;
            PlayerPosPayload.PlayerPos player = players.get(index);
            int rowY = top + 34 + row * PLAYER_PICKER_ROW_HEIGHT;
            boolean hovered = mouseX >= left + 8 && mouseX < left + PLAYER_PICKER_WIDTH - 8
                && mouseY >= rowY && mouseY < rowY + PLAYER_PICKER_ROW_HEIGHT;
            boolean selected = player.uuid().toString().equals(WorldMapConfig.selectedExplorerUuid());
            if (hovered || selected) {
                graphics.fill(left + 8, rowY, left + PLAYER_PICKER_WIDTH - 8,
                    rowY + PLAYER_PICKER_ROW_HEIGHT, selected ? 0xFF54503A : 0xFF41414A);
            }
            float hue = (player.uuid().hashCode() & 0xFFFF) / 65535.0f;
            int playerColor = java.awt.Color.HSBtoRGB(hue, 0.72f, 1.0f) | 0xFF000000;
            graphics.fill(left + 14, rowY + 7, left + 22, rowY + 15, playerColor);
            graphics.text(this.font, player.name(), left + 30, rowY + 6, 0xFFFFFFFF, true);
        }
        if (players.isEmpty()) {
            graphics.centeredText(this.font, "No map users are connected", left + PLAYER_PICKER_WIDTH / 2,
                top + 80, 0xFFB8B8B8);
        }
        graphics.centeredText(this.font, "Cancel", left + PLAYER_PICKER_WIDTH / 2, top + 198, 0xFFCCCCCC);
    }

    private boolean handlePlayerPickerClick(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        int left = (this.width - PLAYER_PICKER_WIDTH) / 2;
        int top = Math.max(8, (this.height - PLAYER_PICKER_HEIGHT) / 2);
        if (event.x() < left || event.x() >= left + PLAYER_PICKER_WIDTH
            || event.y() < top || event.y() >= top + PLAYER_PICKER_HEIGHT
            || event.y() >= top + 188) {
            this.playerPickerOpen = false;
            return true;
        }

        int listTop = top + 34;
        if (event.y() < listTop) return true;
        int row = (int) (event.y() - listTop) / PLAYER_PICKER_ROW_HEIGHT;
        int index = this.playerPickerScroll + row;
        List<PlayerPosPayload.PlayerPos> players = availableMapPlayers();
        if (row < 0 || row >= PLAYER_PICKER_VISIBLE_ROWS || index >= players.size()) return true;

        PlayerPosPayload.PlayerPos selected = players.get(index);
        WorldMapConfig.setSelectedExplorer(selected.uuid().toString(), selected.name());
        this.explorationFilterButton.setMessage(explorationFilterLabel());
        this.explorerSelectButton.setMessage(explorerSelectLabel());
        this.playerPickerOpen = false;
        return true;
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
            case SELECTED_PLAYER -> WorldMapConfig.selectedExplorerName().isBlank()
                ? "Selected player" : WorldMapConfig.selectedExplorerName();
        };
        return Component.literal("Explored by: " + label);
    }

    private static Component explorerSelectLabel() {
        String name = WorldMapConfig.selectedExplorerName();
        return Component.literal(name.isBlank() ? "Choose player to filter by" : "Choose player: " + name);
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
    public boolean mouseClicked(MouseButtonEvent event, boolean isDouble) {
        if (this.playerPickerOpen) return handlePlayerPickerClick(event);
        return super.mouseClicked(event, isDouble);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (this.playerPickerOpen) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                this.playerPickerOpen = false;
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.playerPickerOpen) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int maxScroll = Math.max(0, availableMapPlayers().size() - PLAYER_PICKER_VISIBLE_ROWS);
        this.playerPickerScroll = Math.max(0, Math.min(maxScroll,
            this.playerPickerScroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
