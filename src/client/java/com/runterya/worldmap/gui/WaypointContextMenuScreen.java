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
        Minecraft minecraft = this.minecraft;
        boolean canTeleport = TeleportPermissions.canTeleport(minecraft);
        if (canTeleport) {
            this.addRenderableWidget(Button.builder(Component.literal("Teleport there"), button -> {
                Minecraft currentMinecraft = this.minecraft;
                if (TeleportPermissions.canTeleport(currentMinecraft)) {
                    currentMinecraft.player.connection.sendCommand("tp @s " + (this.waypoint.getX() + 0.5) + " "
                        + this.waypoint.getY() + " " + (this.waypoint.getZ() + 0.5));
                    com.runterya.worldmap.client.ClientPlatform.setScreen(currentMinecraft, null);
                }
            }).bounds(centerX - 100, top, 200, 20).build());
        }
        int actionTop = canTeleport ? top + 24 : top;
        this.addRenderableWidget(Button.builder(Component.literal("Edit"), button -> {
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, new WaypointAddScreen(this.parent, this.waypoint));
        }).bounds(centerX - 100, actionTop, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Remove"), button -> {
            WaypointManager.removeWaypoint(this.waypoint);
            if (this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal("Waypoint removed!"));
            }
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(centerX - 100, actionTop + 24, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent))
            .bounds(centerX - 100, actionTop + 48, 200, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
