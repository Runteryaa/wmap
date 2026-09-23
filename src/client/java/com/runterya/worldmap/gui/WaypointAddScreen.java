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
    private final Waypoint editingWaypoint;

    public WaypointAddScreen(Screen parent, int x, int y, int z, String dimension) {
        super(Component.literal("Add Waypoint"));
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.editingWaypoint = null;
    }

    public WaypointAddScreen(Screen parent, Waypoint waypoint) {
        super(Component.literal("Edit Waypoint"));
        this.parent = parent;
        this.x = waypoint.getX();
        this.y = waypoint.getY();
        this.z = waypoint.getZ();
        this.dimension = waypoint.getDimension();
        this.editingWaypoint = waypoint;
    }

    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private EditBox colorField;
    private int currentColor;
    private boolean showColorWheel = false;
    private boolean isGlobal = false;
    private static final net.minecraft.resources.Identifier COLOR_WHEEL = net.minecraft.resources.Identifier.fromNamespaceAndPath("worldmap", "textures/gui/color_wheel.png");

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameField = new EditBox(this.font, centerX - 100, centerY - 80, 200, 20, Component.literal("Waypoint Name"));
        this.nameField.setValue(this.editingWaypoint == null ? "New Waypoint" : this.editingWaypoint.getName());
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

        this.currentColor = this.editingWaypoint == null ? new java.util.Random().nextInt(0xFFFFFF) : this.editingWaypoint.getColor() & 0xFFFFFF;
        this.colorField = new EditBox(this.font, centerX - 100, centerY - 20, 60, 20, Component.literal("Color Hex"));
        this.colorField.setValue(String.format("%06X", this.currentColor));
        this.colorField.setMaxLength(6);
        this.colorField.setResponder(text -> {
            try {
                this.currentColor = Integer.parseInt(text, 16);
            } catch (Exception ignored) {}
        });
        this.addRenderableWidget(this.colorField);

        if (this.editingWaypoint == null && WaypointManager.isServerWaypointSharingAvailable()) {
            this.addRenderableWidget(Button.builder(visibilityLabel(), button -> {
                this.isGlobal = !this.isGlobal;
                button.setMessage(visibilityLabel());
            }).bounds(centerX + 10, centerY - 20, 180, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal(this.editingWaypoint == null ? "Add" : "Save"), button -> {
            int color = 0xFF000000 | this.currentColor;
            int finalX = this.x;
            int finalY = this.y;
            int finalZ = this.z;
            try { finalX = Integer.parseInt(this.xField.getValue()); } catch (Exception ignored) {}
            try { finalY = Integer.parseInt(this.yField.getValue()); } catch (Exception ignored) {}
            try { finalZ = Integer.parseInt(this.zField.getValue()); } catch (Exception ignored) {}

            Waypoint wp = new Waypoint(this.nameField.getValue(), finalX, finalY, finalZ, color, this.dimension,
                this.editingWaypoint == null ? this.isGlobal : this.editingWaypoint.isGlobal());
            if (this.editingWaypoint == null) WaypointManager.addWaypoint(wp);
            else WaypointManager.updateWaypoint(this.editingWaypoint, wp);
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal(this.editingWaypoint == null ? "Waypoint added!" : "Waypoint updated!"));
            }
            this.minecraft.setScreen(this.parent);
        }).bounds(centerX - 100, centerY + 20, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
            this.minecraft.setScreen(this.parent);
        }).bounds(centerX + 2, centerY + 20, 98, 20).build());
    }

    private Component visibilityLabel() {
        return Component.literal(this.isGlobal ? "[✓] Everyone can see" : "[ ] Everyone can see");
    }

    private long lastClickTime = 0;

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isDouble) {
        double scale = this.minecraft.getWindow().getGuiScale();
        double mouseX = this.minecraft.mouseHandler.xpos() / scale;
        double mouseY = this.minecraft.mouseHandler.ypos() / scale;
        int button = event.button();
        
        long time = System.currentTimeMillis();
        if (time - this.lastClickTime < 200) {
            return false;
        }
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int boxX = centerX - 30;
        int boxY = centerY - 20;

        if (button == 0) {
            // Check if clicked on color preview box
            if (mouseX >= boxX && mouseX <= boxX + 20 && mouseY >= boxY && mouseY <= boxY + 20) {
                this.showColorWheel = !this.showColorWheel;
                this.lastClickTime = time;
                return true;
            }

            // Check if clicked inside color wheel
            if (this.showColorWheel) {
                int wheelSize = 80;
                int wheelHalf = wheelSize / 2;
                int wheelX = centerX + 10;
                int wheelY = centerY - wheelHalf;
                if (mouseX >= wheelX && mouseX <= wheelX + wheelSize && mouseY >= wheelY && mouseY <= wheelY + wheelSize) {
                    double dx = mouseX - (wheelX + wheelHalf);
                    double dy = mouseY - (wheelY + wheelHalf);
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist <= wheelHalf) {
                        double angle = Math.atan2(dy, dx);
                        float hue = (float) (angle / (2 * Math.PI));
                        if (hue < 0) hue += 1.0f;
                        float saturation = (float) (dist / (double)wheelHalf);
                        
                        this.currentColor = java.awt.Color.HSBtoRGB(hue, saturation, 1.0f) & 0xFFFFFF;
                        this.colorField.setValue(String.format("%06X", this.currentColor));
                        this.lastClickTime = time;
                        return true;
                    }
                }
            }
        }
        
        // Hide color wheel if clicked elsewhere
        if (this.showColorWheel) {
            this.showColorWheel = false;
        }

        return super.mouseClicked(event, isDouble);
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Draw Color Wheel if enabled
        if (this.showColorWheel) {
                int wheelSize = 80;
                int wheelHalf = wheelSize / 2;
                int wheelX = centerX + 10;
                int wheelY = centerY - wheelHalf;
                graphics.blit(COLOR_WHEEL, wheelX, wheelY, wheelX + wheelSize, wheelY + wheelSize, 0.0f, 1.0f, 0.0f, 1.0f);
        }

        // Draw color preview box
        int boxX = centerX - 30;
        int boxY = centerY - 20;
        graphics.fill(boxX - 1, boxY - 1, boxX + 21, boxY + 21, 0xFFA0A0A0); // Light gray border
        graphics.fill(boxX, boxY, boxX + 20, boxY + 20, 0xFF000000); // Black border
        graphics.fill(boxX + 1, boxY + 1, boxX + 19, boxY + 19, 0xFF000000 | this.currentColor); // Color
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
