package com.runterya.worldmap.client;

import com.runterya.worldmap.WorldMapConfig;
import com.runterya.worldmap.backend.NetherMapView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Map;
import com.runterya.worldmap.network.PlayerPosPayload.PlayerPos;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.runterya.worldmap.network.MapColorReportPayload;

public class ClientMapManager {
    private static final Map<String, Map<ChunkPos, RegionTexture>> regionsByDimension = new ConcurrentHashMap<>();
    private static List<PlayerPos> otherPlayers = Collections.emptyList();
    private static final Queue<LevelChunk> pendingChunks = new ConcurrentLinkedQueue<>();
    private static final Set<LevelChunk> pendingChunkSet = ConcurrentHashMap.newKeySet();
    /** Chunks that loaded before the server handshake can be re-reported on join. */
    private static final Map<Long, LevelChunk> loadedChunks = new ConcurrentHashMap<>();
    /** Chunks resolved from this client's actual loaded world and tint resources. */
    private static final Set<DimensionChunkKey> locallyResolvedChunks = ConcurrentHashMap.newKeySet();
    private static final Map<DimensionChunkKey, ChunkData> chunkData = new ConcurrentHashMap<>();

    private record DimensionChunkKey(String dimension, long chunkKey) {}
    private record ChunkData(int[] colors, Set<UUID> explorers) {}

    /** Clear all in-memory map data (call on world disconnect). */
    public static void clear() {
        regionsByDimension.values().forEach(regions -> regions.values().forEach(RegionTexture::close));
        regionsByDimension.clear();
        otherPlayers = Collections.emptyList();
        pendingChunks.clear();
        pendingChunkSet.clear();
        locallyResolvedChunks.clear();
        chunkData.clear();
    }

    /** Queue a client-loaded chunk for vanilla-tinted map extraction. */
    public static void queueChunk(LevelChunk chunk) {
        loadedChunks.put(chunkKey(chunk.getPos().x(), chunk.getPos().z()), chunk);
        if (pendingChunkSet.add(chunk)) {
            pendingChunks.offer(chunk);
        }
    }

    /**
     * Biome tint sources sample neighboring positions. When a new chunk arrives,
     * re-extract loaded neighbors too so their edge colors no longer use the
     * temporary fallback tint from before this chunk was available.
     */
    public static void onChunkLoad(LevelChunk chunk) {
        queueChunk(chunk);
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                LevelChunk neighbor = loadedChunks.get(chunkKey(chunkX + dx, chunkZ + dz));
                if (neighbor != null && neighbor.getLevel() == chunk.getLevel()) {
                    queueChunk(neighbor);
                }
            }
        }
    }

    /** Remove chunks that leave the client cache. */
    public static void unloadChunk(LevelChunk chunk) {
        loadedChunks.remove(chunkKey(chunk.getPos().x(), chunk.getPos().z()), chunk);
        pendingChunkSet.remove(chunk);
        pendingChunks.remove(chunk);
    }

    /** Re-extract chunks that were already loaded when the server handshake completed. */
    public static void queueLoadedChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        loadedChunks.entrySet().removeIf(entry -> entry.getValue().getLevel() != minecraft.level);
        for (LevelChunk chunk : loadedChunks.values()) {
            queueChunk(chunk);
        }
    }

    /** Drop tracked chunk references when leaving a world. */
    public static void forgetLoadedChunks() {
        loadedChunks.clear();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }

    /** Process a small number per tick to avoid freezing while chunks stream in. */
    public static void processPendingChunks(int limit) {
        Minecraft minecraft = Minecraft.getInstance();
        for (int processed = 0; processed < limit; processed++) {
            LevelChunk chunk = pendingChunks.poll();
            if (chunk == null) return;
            pendingChunkSet.remove(chunk);

            if (minecraft.level == null || chunk.getLevel() != minecraft.level) continue;

            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            String dimension = minecraft.level.dimension().identifier().toString();
            boolean inNether = NetherMapView.NETHER_DIMENSION.equals(dimension);
            for (NetherMapView view : inNether ? NetherMapView.values() : new NetherMapView[]{NetherMapView.BEDROCK_SURFACE}) {
                String mapDimension = view.storageDimension(dimension);
                int[] colors = ClientMapColorExtractor.extract(chunk, view);
                receiveLocalUpdate(mapDimension, chunkX, chunkZ, colors);
                ClientMapStorage.saveChunk(mapDimension, chunkX, chunkZ, colors,
                    getExplorers(mapDimension, chunkX, chunkZ));

                if (ClientPlayNetworking.canSend(MapColorReportPayload.ID)) {
                    ClientPlayNetworking.send(new MapColorReportPayload(mapDimension, chunkX, chunkZ, colors));
                }
            }
        }
    }

    public static void receiveUpdate(String dimension, int chunkX, int chunkZ, int[] colors, Set<UUID> explorers) {
        DimensionChunkKey key = new DimensionChunkKey(dimension, chunkKey(chunkX, chunkZ));
        ChunkData data = new ChunkData(colors.clone(), Set.copyOf(explorers));
        chunkData.put(key, data);
        if (isVisible(data.explorers())) {
            renderChunk(dimension, chunkX, chunkZ, data.colors());
        }
    }

    private static void renderChunk(String dimension, int chunkX, int chunkZ, int[] colors) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        ChunkPos regionPos = new ChunkPos(regionX, regionZ);

        Map<ChunkPos, RegionTexture> regions = regionsByDimension.computeIfAbsent(dimension, ignored -> new ConcurrentHashMap<>());
        RegionTexture region = regions.computeIfAbsent(regionPos, key -> new RegionTexture(key, dimension));
        region.updateChunk(chunkX & 31, chunkZ & 31, colors);
    }

    /** Apply a locally extracted chunk and protect it from stale network copies. */
    public static void receiveLocalUpdate(String dimension, int chunkX, int chunkZ, int[] colors) {
        DimensionChunkKey key = new DimensionChunkKey(dimension, chunkKey(chunkX, chunkZ));
        locallyResolvedChunks.add(key);
        Set<UUID> explorers = new java.util.HashSet<>(getExplorers(dimension, chunkX, chunkZ));
        if (Minecraft.getInstance().player != null) explorers.add(Minecraft.getInstance().player.getUUID());
        receiveUpdate(dimension, chunkX, chunkZ, colors, explorers);
    }

    /**
     * Server data fills unexplored areas, but a loaded chunk's local extraction
     * is newer and must not be replaced by a delayed packet from another source.
     */
    public static boolean receiveServerUpdate(String dimension, int chunkX, int chunkZ, int[] colors) {
        return receiveServerUpdate(dimension, chunkX, chunkZ, colors, Set.of());
    }

    public static boolean receiveServerUpdate(String dimension, int chunkX, int chunkZ, int[] colors, Set<UUID> explorers) {
        DimensionChunkKey key = new DimensionChunkKey(dimension, chunkKey(chunkX, chunkZ));
        ChunkData previous = chunkData.get(key);
        Set<UUID> mergedExplorers = new java.util.HashSet<>(previous == null ? Set.of() : previous.explorers());
        mergedExplorers.addAll(explorers);
        boolean useServerColor = !locallyResolvedChunks.contains(key);
        int[] mergedColors = useServerColor || previous == null ? colors : previous.colors();
        receiveUpdate(dimension, chunkX, chunkZ, mergedColors, mergedExplorers);
        return useServerColor;
    }

    public static Set<UUID> getExplorers(String dimension, int chunkX, int chunkZ) {
        ChunkData data = chunkData.get(new DimensionChunkKey(dimension, chunkKey(chunkX, chunkZ)));
        return data == null ? Set.of() : data.explorers();
    }

    public static int[] getChunkColors(String dimension, int chunkX, int chunkZ) {
        ChunkData data = chunkData.get(new DimensionChunkKey(dimension, chunkKey(chunkX, chunkZ)));
        return data == null ? null : data.colors();
    }

    public static void refreshLayer() {
        regionsByDimension.values().forEach(regions -> regions.values().forEach(RegionTexture::close));
        regionsByDimension.clear();
        chunkData.forEach((key, data) -> {
            if (isVisible(data.explorers())) {
                int chunkX = (int) (key.chunkKey() >> 32);
                int chunkZ = (int) key.chunkKey();
                renderChunk(key.dimension(), chunkX, chunkZ, data.colors());
            }
        });
    }

    private static boolean isVisible(Set<UUID> explorers) {
        if (!WorldMapConfig.showExploredAreas()) return false;
        return switch (WorldMapConfig.mapLayer()) {
            case MY_EXPLORED -> Minecraft.getInstance().player == null
                || explorers.contains(Minecraft.getInstance().player.getUUID());
            case OTHERS_EXPLORED -> Minecraft.getInstance().player == null
                || explorers.stream().anyMatch(id -> !id.equals(Minecraft.getInstance().player.getUUID()));
            case ALL -> true;
            case SELECTED_PLAYER -> {
                try {
                    yield explorers.contains(UUID.fromString(WorldMapConfig.selectedExplorerUuid()));
                } catch (IllegalArgumentException exception) {
                    yield false;
                }
            }
        };
    }

    public static void updatePlayerPositions(List<PlayerPos> positions) {
        otherPlayers = positions;
    }

    public static List<PlayerPos> getOtherPlayers() {
        return otherPlayers;
    }

    public static Map<ChunkPos, RegionTexture> getRegions(String dimension) {
        return regionsByDimension.getOrDefault(dimension, Collections.emptyMap());
    }

    public static RegionTexture getRegion(String dimension, int regionX, int regionZ) {
        return getRegions(dimension).get(new ChunkPos(regionX, regionZ));
    }

    public static class RegionTexture {
        private final ChunkPos regionPos;
        private final int rx;
        private final int rz;
        private final String textureDimensionKey;
        private final NativeImage image;
        private DynamicTexture texture;
        private Identifier textureLocation;
        private boolean isDirty = false;

        public RegionTexture(ChunkPos regionPos, String dimension) {
            this.regionPos = regionPos;
            this.rx = regionPos.getMinBlockX() >> 4;
            this.rz = regionPos.getMinBlockZ() >> 4;
            this.textureDimensionKey = java.util.UUID.nameUUIDFromBytes(dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
            this.image = new NativeImage(NativeImage.Format.RGBA, 512, 512, true);
        }

        public void updateChunk(int localChunkX, int localChunkZ, int[] colors) {
            int startX = localChunkX * 16;
            int startZ = localChunkZ * 16;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int argb = colors[z * 16 + x];
                    // Ensure opaque
                    int a = (argb >> 24) & 0xFF;
                    if (a == 0) a = 0xFF;
                    argb = (a << 24) | (argb & 0xFFFFFF);
                    
                    // NativeImage expects ARGB in modern versions
                    this.image.setPixel(startX + x, startZ + z, argb);
                }
            }
            this.isDirty = true;
        }

        public Identifier getTextureLocation() {
            if (this.texture == null) {
                this.texture = new DynamicTexture(() -> "worldmap_region", this.image);
                this.textureLocation = Identifier.fromNamespaceAndPath(
                    "worldmap", "region_" + textureDimensionKey + "_" + rx + "_" + rz
                );
                Minecraft.getInstance().getTextureManager().register(this.textureLocation, this.texture);
            }
            if (this.isDirty) {
                this.texture.upload();
                this.isDirty = false;
            }
            return this.textureLocation;
        }

        public void close() {
            if (this.texture != null) {
                this.texture.close();
            } else {
                this.image.close();
            }
        }
    }
}
