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
        int top = this.height / 2 - 28;
        Minecraft minecraft = this.minecraft;
        boolean canTeleport = TeleportPermissions.canTeleport(minecraft);
        if (canTeleport) {
            this.addRenderableWidget(Button.builder(Localization.component("waypoint_menu.teleport"), button -> {
                Minecraft currentMinecraft = this.minecraft;
                if (TeleportPermissions.canTeleport(currentMinecraft)) {
                    currentMinecraft.player.connection.sendCommand("tp @s " + (this.waypoint.getX() + 0.5) + " "
                        + this.waypoint.getY() + " " + (this.waypoint.getZ() + 0.5));
                    com.runterya.worldmap.client.ClientPlatform.setScreen(currentMinecraft, null);
                }
            }).bounds(centerX - 100, top, 200, 20).build());
        }
        int actionTop = canTeleport ? top + 24 : top;
        this.addRenderableWidget(Button.builder(Localization.component("waypoint_menu.edit"), button -> {
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, new WaypointAddScreen(this.parent, this.waypoint));
        }).bounds(centerX - 100, actionTop, 200, 20).build());
        this.addRenderableWidget(Button.builder(Localization.component("waypoint_menu.remove"), button -> {
            WaypointManager.removeWaypoint(this.waypoint);
            if (this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Localization.component("waypoint_menu.removed"));
            }
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(centerX - 100, actionTop + 24, 200, 20).build());
        this.addRenderableWidget(Button.builder(Localization.component("waypoint_menu.back"), button -> com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent))
            .bounds(centerX - 100, actionTop + 48, 200, 20).build());
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        if (!this.waypoint.getCategory().isBlank()) {
            graphics.centeredText(this.font, Localization.text("waypoint_menu.category", this.waypoint.getCategory()),
                centerX, centerY - 73, 0xFFCCCCCC);
        }
        if (!this.waypoint.getNote().isBlank()) {
            String note = Localization.text("waypoint_menu.note", this.waypoint.getNote());
            int maxWidth = Math.min(360, this.width - 24);
            while (!note.isEmpty() && this.font.width(note) > maxWidth) {
                note = note.substring(0, note.length() - 1);
            }
            graphics.centeredText(this.font, note, centerX, centerY - 59, 0xFFB8B8B8);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
