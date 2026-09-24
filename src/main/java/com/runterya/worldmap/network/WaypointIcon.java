package com.runterya.worldmap.network;

import net.minecraft.resources.Identifier;

/** Normalizes waypoint item IDs and migrates icons saved by older WorldMap versions. */
public final class WaypointIcon {
    private WaypointIcon() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NONE")) return "";
        String legacyItem = switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "VILLAGE" -> "minecraft:bell";
            case "PORTAL" -> "minecraft:ender_eye";
            case "FARM" -> "minecraft:wheat";
            case "HOME" -> "minecraft:oak_door";
            case "STORAGE" -> "minecraft:chest_minecart";
            case "MINE" -> "minecraft:iron_pickaxe";
            default -> value;
        };
        Identifier identifier = Identifier.tryParse(legacyItem);
        return identifier == null ? "" : identifier.toString();
    }
}
