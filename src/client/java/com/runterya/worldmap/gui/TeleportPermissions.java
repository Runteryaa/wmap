package com.runterya.worldmap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.server.permissions.Permissions;

/** Shared permission check for teleport actions that run the vanilla /tp command. */
final class TeleportPermissions {
    private TeleportPermissions() {
    }

    static boolean canTeleport(Minecraft minecraft) {
        return minecraft != null
            && minecraft.player != null
            && minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
