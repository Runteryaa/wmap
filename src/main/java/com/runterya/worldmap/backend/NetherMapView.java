package com.runterya.worldmap.backend;

/** The Nether map can show its bedrock ceiling or a player-height cave layer. */
public enum NetherMapView {
    BEDROCK_SURFACE,
    CAVE_LAYER;

    public static final String NETHER_DIMENSION = "minecraft:the_nether";
    public static final int CAVE_LAYER_STEP = 8;
    private static final String LEGACY_MID_LEVEL_SUFFIX = "#worldmap:nether_fixed_scan_y40_v5";
    private static final String CAVE_LAYER_SUFFIX = "#worldmap:nether_cave_y_";

    /** Quantize the player's feet Y to the lower edge of its 8-block map layer. */
    public static int getPlayerLayerY(int playerY, int minY, int maxY) {
        int layerY = Math.floorDiv(playerY, CAVE_LAYER_STEP) * CAVE_LAYER_STEP;
        int minLayer = -Math.floorDiv(-minY, CAVE_LAYER_STEP) * CAVE_LAYER_STEP;
        int maxLayer = Math.floorDiv(maxY - 1, CAVE_LAYER_STEP) * CAVE_LAYER_STEP;
        return Math.max(minLayer, Math.min(maxLayer, layerY));
    }

    /** Quantize the player's height while respecting any per-dimension top-layer cap. */
    public static int getPlayerLayerY(String dimension, int playerY, int minY, int maxY) {
        return LayeredDimensions.clampLayerY(dimension, playerY, minY, maxY);
    }

    /** Get a neighboring layer while keeping it on the dimension's 8-block grid. */
    public static int getNearbyPlayerLayerY(int centerLayerY, int offset, int minY, int maxY) {
        int minLayer = -Math.floorDiv(-minY, CAVE_LAYER_STEP) * CAVE_LAYER_STEP;
        int maxLayer = Math.floorDiv(maxY - 1, CAVE_LAYER_STEP) * CAVE_LAYER_STEP;
        int layerY = centerLayerY + offset * CAVE_LAYER_STEP;
        return Math.max(minLayer, Math.min(maxLayer, layerY));
    }

    /** Get a neighboring slice without crossing a dimension's configured top layer. */
    public static int getNearbyPlayerLayerY(String dimension, int centerLayerY, int offset, int minY, int maxY) {
        return LayeredDimensions.clampLayerY(dimension,
            getNearbyPlayerLayerY(centerLayerY, offset, minY, maxY), minY, maxY);
    }

    public String storageDimension(String dimension) {
        return this == CAVE_LAYER && LayeredDimensions.contains(dimension)
            ? storageDimension(dimension, 40) : dimension;
    }

    public String storageDimension(String dimension, int layerY) {
        if (this != CAVE_LAYER) return dimension;
        // Reuse the existing Y=40 Nether data as its matching cave layer.
        // Other opted-in dimensions need independent per-dimension layer keys.
        if (layerY == 40 && NETHER_DIMENSION.equals(dimension)) {
            return dimension + LEGACY_MID_LEVEL_SUFFIX;
        }
        return dimension + CAVE_LAYER_SUFFIX + layerY + "_v1";
    }

    /** Remove view suffixes used to isolate separately cached map images. */
    public static String gameDimension(String storageDimension) {
        int legacy = storageDimension.indexOf(LEGACY_MID_LEVEL_SUFFIX);
        if (legacy >= 0) return storageDimension.substring(0, legacy);
        int caveLayer = storageDimension.indexOf(CAVE_LAYER_SUFFIX);
        return caveLayer >= 0 ? storageDimension.substring(0, caveLayer) : storageDimension;
    }

    public static boolean isCaveLayerDimension(String storageDimension) {
        return storageDimension.contains(CAVE_LAYER_SUFFIX)
            || storageDimension.endsWith(LEGACY_MID_LEVEL_SUFFIX);
    }

    /** Read a cave layer's scan height from its storage dimension key. */
    public static int getCaveLayerY(String storageDimension) {
        if (storageDimension.endsWith(LEGACY_MID_LEVEL_SUFFIX)) return 40;
        int suffix = storageDimension.indexOf(CAVE_LAYER_SUFFIX);
        if (suffix < 0) return Integer.MIN_VALUE;
        int start = suffix + CAVE_LAYER_SUFFIX.length();
        int end = storageDimension.indexOf("_v", start);
        if (end < 0) return Integer.MIN_VALUE;
        try {
            return Integer.parseInt(storageDimension.substring(start, end));
        } catch (NumberFormatException exception) {
            return Integer.MIN_VALUE;
        }
    }
}
