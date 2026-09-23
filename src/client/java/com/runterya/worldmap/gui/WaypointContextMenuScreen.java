package com.runterya.worldmap.gui;

import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class WaypointContextMenuScreen extends Screen {
    private final Screen parent;
    private final Waypoint waypoint;

    public WaypointContextMenuScreen(Screen parent, Waypoint waypoint) {
        super(Component.literal(waypoint.getName()));
        this.parent = parent;
        this.waypoint = waypoint;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 2 - 42;
        this.addRenderableWidget(Button.builder(Component.literal("Teleport there"), button -> {
            Minecraft minecraft = this.minecraft;
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.connection.sendCommand("tp @s " + (this.waypoint.getX() + 0.5) + " "
                    + this.waypoint.getY() + " " + (this.waypoint.getZ() + 0.5));
                minecraft.setScreen(null);
            }
        }).bounds(centerX - 100, top, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Edit"), button -> {
            this.minecraft.setScreen(new WaypointAddScreen(this.parent, this.waypoint));
        }).bounds(centerX - 100, top + 24, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Remove"), button -> {
            WaypointManager.removeWaypoint(this.waypoint);
            if (this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal("Waypoint removed!"));
            }
            this.minecraft.setScreen(this.parent);
        }).bounds(centerX - 100, top + 48, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.minecraft.setScreen(this.parent))
            .bounds(centerX - 100, top + 72, 200, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
