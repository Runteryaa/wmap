package com.runterya.worldmap.client.waypoint;

public class Waypoint {
    private String name;
    private int x;
    private int y;
    private int z;
    private int color; // ARGB
    private String dimension;
    private boolean isGlobal;

    public Waypoint(String name, int x, int y, int z, int color, String dimension, boolean isGlobal) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.dimension = dimension;
        this.isGlobal = isGlobal;
    }

    public String getName() { return name; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getColor() { return color; }
    public String getDimension() { return dimension; }
    public boolean isGlobal() { return isGlobal; }
}
