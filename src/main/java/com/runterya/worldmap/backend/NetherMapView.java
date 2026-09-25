package com.runterya.worldmap.backend;

/** The Nether map can use its existing top surface or a vertical mid-level slice. */
public enum NetherMapView {
    BEDROCK_SURFACE,
    MID_LEVEL;

    public static final String NETHER_DIMENSION = "minecraft:the_nether";
    private static final String MID_LEVEL_SUFFIX = "#worldmap:nether_mid";

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
