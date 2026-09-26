package com.runterya.worldmap.client.waypoint;

import com.runterya.worldmap.network.WaypointIcon;

public class Waypoint {
    private String name;
    private int x;
    private int y;
    private int z;
    private int color; // ARGB
    private String dimension;
    private boolean isGlobal;
    private String worldId;
    private String globalId;
    private String icon = "";
    private String category = "";
    private String note = "";
    private String creatorUuid = "";
    private String creatorName = "";

    public Waypoint(String name, int x, int y, int z, int color, String dimension, boolean isGlobal) {
        this(name, x, y, z, color, dimension, isGlobal, "");
    }

    public Waypoint(String name, int x, int y, int z, int color, String dimension, boolean isGlobal, String worldId) {
        this(name, x, y, z, color, dimension, isGlobal, worldId, "");
    }

    public Waypoint(String name, int x, int y, int z, int color, String dimension, boolean isGlobal,
                    String worldId, String icon) {
        this(name, x, y, z, color, dimension, isGlobal, worldId, icon, "", "");
    }

    public Waypoint(String name, int x, int y, int z, int color, String dimension, boolean isGlobal,
                    String worldId, String icon, String category, String note) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.dimension = dimension;
        this.isGlobal = isGlobal;
        this.worldId = worldId;
        this.icon = WaypointIcon.normalize(icon);
        setCategory(category);
        setNote(note);
    }

    public String getName() { return name; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getColor() { return color; }
    public String getDimension() { return dimension; }
    public boolean isGlobal() { return isGlobal; }
    public void setGlobal(boolean global) { this.isGlobal = global; }
    public String getWorldId() { return worldId == null ? "" : worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
    public String getGlobalId() { return globalId == null ? "" : globalId; }
    public void setGlobalId(String globalId) { this.globalId = globalId; }
    public String getCreatorUuid() { return creatorUuid == null ? "" : creatorUuid; }
    public void setCreatorUuid(String creatorUuid) { this.creatorUuid = creatorUuid == null ? "" : creatorUuid; }
    public String getCreatorName() { return creatorName == null ? "" : creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName == null ? "" : creatorName; }
    public String getIcon() {
        this.icon = WaypointIcon.normalize(this.icon);
        return this.icon;
    }
    public void setIcon(String icon) { this.icon = WaypointIcon.normalize(icon); }
    public String getCategory() { return category == null ? "" : category; }
    public void setCategory(String category) {
        this.category = category == null ? "" : category.trim();
        if (this.category.length() > 48) this.category = this.category.substring(0, 48);
    }
    public String getNote() { return note == null ? "" : note; }
    public void setNote(String note) {
        this.note = note == null ? "" : note.trim();
        if (this.note.length() > 256) this.note = this.note.substring(0, 256);
    }
}
