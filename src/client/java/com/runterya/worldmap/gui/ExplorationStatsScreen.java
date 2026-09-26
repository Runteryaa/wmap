package com.runterya.worldmap.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.runterya.worldmap.backend.NetherMapView;
import com.runterya.worldmap.client.ClientMapStorage;
import com.runterya.worldmap.client.ExplorationStatistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Displays a background-scanned summary of the current local world/server map archive. */
public final class ExplorationStatsScreen extends Screen {
    private static final int PANEL_WIDTH = 440;
    private final Screen parent;
    private ExplorationStatistics statistics;
    private String error;
    private int scroll;
    private Button doneButton;

    public ExplorationStatsScreen(Screen parent) {
        super(Localization.component("statistics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.doneButton = this.addRenderableWidget(Button.builder(Localization.component("settings.done"), button -> goBack())
            .bounds(this.width / 2 - 100, this.height - 34, 200, 22).build());
        UUID playerUuid = this.minecraft != null && this.minecraft.player != null
            ? this.minecraft.player.getUUID() : null;
        ClientMapStorage.loadExplorationStatistics(playerUuid).whenComplete((result, failure) -> {
            Minecraft.getInstance().execute(() -> {
                if (!com.runterya.worldmap.client.ClientPlatform.isScreen(
                    Minecraft.getInstance(), ExplorationStatsScreen.class)) return;
                if (failure != null) {
                    this.error = Localization.text("statistics.error");
                } else {
                    this.statistics = result;
                    this.scroll = 0;
                }
            });
        });
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        int width = Math.min(PANEL_WIDTH, this.width - 24);
        int left = (this.width - width) / 2;
        int top = 18;
        int bottom = this.height - 44;
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);
        graphics.fill(left, top, left + width, bottom, 0xE0181A20);
        graphics.outline(left, top, width, bottom - top, 0xFF777777);
        graphics.centeredText(this.font, Localization.text("statistics.title"), this.width / 2, top + 12, 0xFFFFFFFF);
        graphics.centeredText(this.font, Localization.text("statistics.subtitle"), this.width / 2,
            top + 31, 0xFFB8B8B8);

        if (this.statistics == null) {
            String message = this.error == null ? Localization.text("statistics.loading") : this.error;
            graphics.centeredText(this.font, message, this.width / 2, top + 74,
                this.error == null ? 0xFFCCCCCC : 0xFFFF9999);
        } else {
            int contentLeft = left + 18;
            graphics.text(this.font, Localization.text("statistics.map_chunks", format(this.statistics.totalChunks())),
                contentLeft, top + 58, 0xFFFFFFFF, true);
            graphics.text(this.font, Localization.text("statistics.player_chunks", format(this.statistics.playerChunks())),
                contentLeft, top + 76, 0xFFFFFFFF, true);
            graphics.text(this.font, Localization.text("statistics.total_area",
                format(this.statistics.totalChunks() * 256L)),
                contentLeft, top + 94, 0xFFB8B8B8, true);
            graphics.text(this.font, Localization.text("statistics.player_area",
                format(this.statistics.playerChunks() * 256L)),
                contentLeft, top + 112, 0xFFB8B8B8, true);
            graphics.fill(contentLeft, top + 133, left + width - 18, top + 134, 0xFF55555F);
            graphics.text(this.font, Localization.text("statistics.by_dimension"), contentLeft, top + 141, 0xFFFFFFFF, true);

            List<Map.Entry<String, Long>> dimensions = this.statistics.chunksByDimension().entrySet().stream()
                .sorted(Comparator.comparing(entry -> dimensionLabel(entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                .toList();
            int listTop = top + 163;
            int listBottom = bottom - 12;
            int visibleRows = Math.max(1, (listBottom - listTop) / 19);
            int maxScroll = Math.max(0, dimensions.size() - visibleRows);
            this.scroll = Math.max(0, Math.min(maxScroll, this.scroll));
            graphics.enableScissor(contentLeft, listTop, left + width - 18, listBottom);
            for (int row = 0; row < visibleRows && this.scroll + row < dimensions.size(); row++) {
                Map.Entry<String, Long> dimension = dimensions.get(this.scroll + row);
                long playerCount = this.statistics.playerChunksByDimension().getOrDefault(dimension.getKey(), 0L);
                int y = listTop + row * 19;
                String count = Localization.text("statistics.dimension_counts", format(dimension.getValue()), format(playerCount));
                String label = dimensionLabel(dimension.getKey());
                int maxLabelWidth = Math.max(24, left + width - 30 - contentLeft - this.font.width(count) - 12);
                while (!label.isEmpty() && this.font.width(label) > maxLabelWidth) {
                    label = label.substring(0, label.length() - 1);
                }
                graphics.text(this.font, label, contentLeft, y, 0xFFE7E7E7, true);
                graphics.text(this.font, count, left + width - 22 - this.font.width(count), y, 0xFFB8B8B8, true);
            }
            graphics.disableScissor();
            if (dimensions.isEmpty()) {
                graphics.text(this.font, Localization.text("statistics.empty"), contentLeft, listTop, 0xFFB8B8B8, true);
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static String dimensionLabel(String storageDimension) {
        String dimension = NetherMapView.gameDimension(storageDimension);
        String label = switch (dimension) {
            case "minecraft:overworld" -> Localization.text("dimension.overworld");
            case "minecraft:the_nether" -> Localization.text("dimension.nether");
            case "minecraft:the_end" -> Localization.text("dimension.end");
            default -> dimension;
        };
        if (NetherMapView.isCaveLayerDimension(storageDimension)) {
            int layerY = NetherMapView.getCaveLayerY(storageDimension);
            if (layerY != Integer.MIN_VALUE) label = Localization.text("statistics.layer_y", label, layerY);
        }
        return label;
    }

    private static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private void goBack() {
        com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.statistics != null) {
            this.scroll = Math.max(0, this.scroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            goBack();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
