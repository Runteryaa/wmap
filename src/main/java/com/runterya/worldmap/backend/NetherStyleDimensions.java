package com.runterya.worldmap.backend;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

/** Resolves dimensions that should use WMap's vertical cave-layer renderer. */
public final class NetherStyleDimensions {
    public static final TagKey<DimensionType> LAYERED_TYPE_TAG = TagKey.create(
        Registries.DIMENSION_TYPE,
        Identifier.fromNamespaceAndPath("worldmap", "wmap-layered-type")
    );

    private NetherStyleDimensions() {}

    /** Checks a live level, using both its dimension ID and its tagged type. */
    public static boolean isNetherStyle(Level level) {
        if (level == null) return false;
        return NetherMapView.NETHER_DIMENSION.equals(level.dimension().identifier().toString())
            || level.dimensionTypeRegistration().is(LAYERED_TYPE_TAG);
    }

    /** Checks a dimension in the active world's registry, including unloaded dimensions. */
    public static boolean isNetherStyle(String dimensionId, Registry<LevelStem> levelStems) {
        if (NetherMapView.NETHER_DIMENSION.equals(dimensionId)) return true;
        Identifier id = Identifier.tryParse(dimensionId);
        if (id == null) return false;
        return levelStems.get(id)
            .map(Holder::value)
            .map(LevelStem::type)
            .map(type -> type.is(LAYERED_TYPE_TAG))
            .orElse(false);
    }
}
