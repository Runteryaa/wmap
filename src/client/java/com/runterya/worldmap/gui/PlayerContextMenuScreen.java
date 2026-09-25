package com.runterya.worldmap.gui;

import com.runterya.worldmap.network.PlayerPosPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Context actions for another player's world-map marker. */
public final class PlayerContextMenuScreen extends Screen {
    private final Screen parent;
    private final PlayerPosPayload.PlayerPos player;

    public PlayerContextMenuScreen(Screen parent, PlayerPosPayload.PlayerPos player) {
        super(Component.literal(player.name()));
        this.parent = parent;
        this.player = player;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 2 - 22;
        Minecraft minecraft = this.minecraft;
        boolean canTeleport = TeleportPermissions.canTeleport(minecraft);
        if (canTeleport) {
            this.addRenderableWidget(Button.builder(Component.literal("Teleport to " + this.player.name()), button -> {
                Minecraft currentMinecraft = this.minecraft;
                if (TeleportPermissions.canTeleport(currentMinecraft)) {
                    currentMinecraft.player.connection.sendCommand("tp @s " + this.player.name());
                    com.runterya.worldmap.client.ClientPlatform.setScreen(currentMinecraft, null);
                }
            }).bounds(centerX - 110, top, 220, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Back"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(centerX - 110, top + (canTeleport ? 24 : 0), 220, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
