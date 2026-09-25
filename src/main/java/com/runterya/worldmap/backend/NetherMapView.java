package com.runterya.worldmap.backend;

/** The Nether map can use its existing top surface or a vertical mid-level slice. */
public enum NetherMapView {
    BEDROCK_SURFACE,
    MID_LEVEL;

    public static final String NETHER_DIMENSION = "minecraft:the_nether";
    // Better Nether Map uses a fixed Nether starting height of Y=40 and lets
    // vanilla map sampling find the first visible map-colored block below it.
    public static final int MID_LEVEL_SCAN_START_Y = 40;
    private static final String MID_LEVEL_SUFFIX = "#worldmap:nether_fixed_scan_y40_v5";

    public static int getMidLevelScanStartY(int minY, int maxY) {
        return Math.max(minY + 1, Math.min(maxY - 1, MID_LEVEL_SCAN_START_Y));
    }

    public String storageDimension(String dimension) {
        return this == MID_LEVEL && NETHER_DIMENSION.equals(dimension)
            ? dimension + MID_LEVEL_SUFFIX
            : dimension;
    }

    /** Remove the view suffix used only to distinguish map color datasets. */
    public static String gameDimension(String storageDimension) {
        return storageDimension.endsWith(MID_LEVEL_SUFFIX)
            ? storageDimension.substring(0, storageDimension.length() - MID_LEVEL_SUFFIX.length())
            : storageDimension;
    }

    public static boolean isMidLevelDimension(String storageDimension) {
        return storageDimension.endsWith(MID_LEVEL_SUFFIX);
    }
}
