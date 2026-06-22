package com.runterya.worldmap.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;

import java.util.Random;

public class WaypointAddScreen extends Screen {
    private EditBox nameField;
    private final int x, y, z;
    private final String dimension;
    private final Screen parent;

    public WaypointAddScreen(Screen parent, int x, int y, int z, String dimension) {
        super(Component.literal("Add Waypoint"));
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
    }

    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private EditBox colorField;
    private int currentColor;

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameField = new EditBox(this.font, centerX - 100, centerY - 80, 200, 20, Component.literal("Waypoint Name"));
        this.nameField.setValue("New Waypoint");
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        this.xField = new EditBox(this.font, centerX - 100, centerY - 50, 60, 20, Component.literal("X"));
        this.xField.setValue(String.valueOf(this.x));
        this.addRenderableWidget(this.xField);

        this.yField = new EditBox(this.font, centerX - 30, centerY - 50, 60, 20, Component.literal("Y"));
        this.yField.setValue(String.valueOf(this.y));
        this.addRenderableWidget(this.yField);

        this.zField = new EditBox(this.font, centerX + 40, centerY - 50, 60, 20, Component.literal("Z"));
        this.zField.setValue(String.valueOf(this.z));
        this.addRenderableWidget(this.zField);

        this.currentColor = new Random().nextInt(0xFFFFFF);
        this.colorField = new EditBox(this.font, centerX - 100, centerY - 20, 60, 20, Component.literal("Color"));
        this.colorField.setValue(String.format("%06X", this.currentColor));
        this.colorField.setMaxLength(6);
        this.colorField.setResponder(text -> {
            try {
                this.currentColor = Integer.parseInt(text, 16);
            } catch (Exception ignored) {}
        });
        this.addRenderableWidget(this.colorField);

        this.addRenderableWidget(Button.builder(Component.literal("Add"), button -> {
            int color = 0xFF000000 | this.currentColor;
            int finalX = this.x;
            int finalY = this.y;
            int finalZ = this.z;
            try { finalX = Integer.parseInt(this.xField.getValue()); } catch (Exception ignored) {}
            try { finalY = Integer.parseInt(this.yField.getValue()); } catch (Exception ignored) {}
            try { finalZ = Integer.parseInt(this.zField.getValue()); } catch (Exception ignored) {}

            Waypoint wp = new Waypoint(this.nameField.getValue(), finalX, finalY, finalZ, color, this.dimension);
            WaypointManager.addWaypoint(wp);
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal("Waypoint added!"));
            }
            this.minecraft.setScreen(this.parent);
        }).bounds(centerX - 100, centerY + 20, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
            this.minecraft.setScreen(this.parent);
        }).bounds(centerX + 2, centerY + 20, 98, 20).build());
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Draw color preview box
        int boxX = centerX - 30;
        int boxY = centerY - 20;
        graphics.fill(boxX, boxY, boxX + 20, boxY + 20, 0xFF000000 | this.currentColor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
