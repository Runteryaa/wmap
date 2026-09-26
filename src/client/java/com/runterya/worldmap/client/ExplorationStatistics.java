package com.runterya.worldmap.client;

import java.util.Map;

/** A snapshot of discovered map chunks in the current local map archive. */
public record ExplorationStatistics(
    long totalChunks,
    long playerChunks,
    Map<String, Long> chunksByDimension,
    Map<String, Long> playerChunksByDimension
) {
    public ExplorationStatistics {
        chunksByDimension = Map.copyOf(chunksByDimension);
        playerChunksByDimension = Map.copyOf(playerChunksByDimension);
    }
}
