package com.runterya.worldmap.network;

/** Vanilla item textures used as waypoint markers on the map. */
public enum WaypointIcon {
    NONE("None", ""),
    VILLAGE("Village", "item/bell.png"),
    PORTAL("Portal", "item/ender_eye.png"),
    FARM("Farm", "item/wheat.png"),
    HOME("Home", "item/oak_door.png"),
    STORAGE("Storage", "item/chest_minecart.png"),
    MINE("Mine", "item/iron_pickaxe.png");

    private final String label;
    private final String texturePath;

    WaypointIcon(String label, String texturePath) {
        this.label = label;
        this.texturePath = texturePath;
    }

    public String label() {
        return label;
    }

    public String texturePath() {
        return texturePath;
    }
}
