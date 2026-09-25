package com.runterya.worldmap.backend;

/** The Nether map can use its existing top surface or a vertical mid-level slice. */
public enum NetherMapView {
    BEDROCK_SURFACE,
    MID_LEVEL;

    public static final String NETHER_DIMENSION = "minecraft:the_nether";
    // Vanilla's Nether logical height is 128 blocks (Y 0..127 in 26.1.2),
    // even though its physical build height reaches Y 256. A 34-block band
    // around the logical midpoint includes the lava sea near Y 31 and levels
    // around Y 90, while staying clear of the bedrock ceiling and floor.
    public static final int MID_LEVEL_BAND_RADIUS = 34;
    private static final String MID_LEVEL_SUFFIX = "#worldmap:nether_mid_playable_center_v3";

    public static int getMidLevelY(int minY, int maxY, int logicalHeight) {
        int logicalMaxYExclusive = Math.min(maxY, minY + Math.max(1, logicalHeight));
        return minY + Math.max(0, logicalMaxYExclusive - minY) / 2;
    }

    public static int getMidLevelMinY(int minY, int maxY, int logicalHeight) {
        int centerY = getMidLevelY(minY, maxY, logicalHeight);
        return Math.max(minY, centerY - MID_LEVEL_BAND_RADIUS);
    }

    public static int getMidLevelMaxY(int minY, int maxY, int logicalHeight) {
        int centerY = getMidLevelY(minY, maxY, logicalHeight);
        int logicalMaxY = Math.min(maxY - 1, minY + Math.max(1, logicalHeight) - 1);
        return Math.min(logicalMaxY, centerY + MID_LEVEL_BAND_RADIUS);
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
