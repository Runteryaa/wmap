package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import com.runterya.worldmap.backend.NetherMapView;
import com.runterya.worldmap.backend.LayeredDimensions;
import com.runterya.worldmap.client.ClientMapManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Locale;

public class WorldMapScreen extends Screen {
    private static final double MIN_SCALE = 0.025;
    private static final double MAX_SCALE = 10.0;
    private static final net.minecraft.resources.Identifier PLAYER_MARKER =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "textures/map/decorations/player.png");

    private double panX = 0;
    private double panY = 0;
    private double scale = 1.0;
    private EditBox waypointSearchField;
    private int searchScrollOffset;
    private static final int SEARCH_PANEL_WIDTH = 280;
    private static final int SEARCH_ROW_HEIGHT = 22;
    private static final int MAX_SEARCH_RESULTS = 8;
    private static final int SEARCH_RESULTS_TOP = 31;
    private static final int PLAYER_SUGGESTION_HEIGHT = 22;
    private static final int ITEM_PICKER_COLUMNS = 9;
    private static final int ITEM_PICKER_ROWS = 5;
    private record SearchItemEntry(Identifier id, ItemStack stack, String searchName) {}
    private record WaypointSearchResult(
        com.runterya.worldmap.client.waypoint.Waypoint waypoint,
        com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player
    ) {}
    private Button itemSearchButton;
    private Button settingsButton;
    private Button netherViewButton;
    private Button dimensionButton;
    private String selectedDimension;
    private boolean dimensionPickerOpen;
    private int dimensionPickerOffset;
    private boolean itemPickerOpen;
    private String itemPickerSearch = "";
    private int itemPickerScrollRow;
    private String selectedSearchItem = "";
    private EditBox itemPickerSearchField;
    private Button itemPickerClearButton;
    private List<SearchItemEntry> allSearchItems = List.of();
    private List<SearchItemEntry> filteredSearchItems = List.of();

    public WorldMapScreen() {
        super(Localization.component("map.title"));
        
        // Center the map on the local player if they exist
        if (Minecraft.getInstance().player != null) {
            this.panX = -Minecraft.getInstance().player.getX();
            this.panY = -Minecraft.getInstance().player.getZ();
        }
        if (Minecraft.getInstance().level != null) {
            this.selectedDimension = Minecraft.getInstance().level.dimension().identifier().toString();
        } else {
            this.selectedDimension = "minecraft:overworld";
        }
    }

    @Override
    protected void init() {
        this.settingsButton = this.addRenderableWidget(Button.builder(Localization.component("map.settings"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(
                this.minecraft, new WorldMapConfigScreen(this)
            )
        ).bounds(8, this.height - 28, 100, 20).build());
        this.netherViewButton = this.addRenderableWidget(Button.builder(netherViewLabel(activeDimension()), button -> {
            Minecraft minecraft = Minecraft.getInstance();
            boolean playerInSelectedDimension = minecraft.level != null && minecraft.player != null
                && minecraft.level.dimension().identifier().toString().equals(activeDimension());
            int minY = playerInSelectedDimension ? minecraft.level.getMinY() : -64;
            int maxY = playerInSelectedDimension ? minecraft.level.getMaxY() : 256;
            com.runterya.worldmap.client.ClientPlatform.setScreen(
                this.minecraft, new NetherLayerSelectionScreen(this, minY, maxY, activeDimension())
            );
        }).bounds(112, this.height - 28, 150, 20).build());
        this.netherViewButton.visible = isNetherStyleDimension(activeDimension());
        this.netherViewButton.active = this.netherViewButton.visible;
        this.dimensionButton = this.addRenderableWidget(Button.builder(dimensionButtonLabel(), button -> {
            setWaypointItemPickerOpen(false);
            this.dimensionPickerOpen = !this.dimensionPickerOpen;
            this.dimensionPickerOffset = 0;
        }).bounds(Math.max(8, this.width - 190), this.height - 28, 182, 20).build());
        this.waypointSearchField = new EditBox(this.font,
            Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8), 8,
            Math.min(190, this.width - 110), 20,
            Localization.component("map.search_waypoints"));
        this.waypointSearchField.setHint(Localization.component("map.search_waypoint_name"));
        this.waypointSearchField.setMaxLength(64);
        this.waypointSearchField.setResponder(text -> this.searchScrollOffset = 0);
        this.addRenderableWidget(this.waypointSearchField);
        this.itemSearchButton = this.addRenderableWidget(Button.builder(
            Localization.component(this.selectedSearchItem.isBlank() ? "map.choose_item" : "map.item_selected"), button ->
            openWaypointItemPicker()
        ).bounds(Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8) + 196, 8,
            Math.min(76, Math.max(40, this.width - 224)), 20).build());

        int pickerX = Math.max(8, (this.width - 290) / 2);
        int pickerY = Math.max(8, (this.height - 238) / 2);
        this.itemPickerSearchField = new EditBox(this.font, pickerX + 9, pickerY + 25, 245, 20,
            Localization.component("map.search_items_by_name"));
        this.itemPickerSearchField.setHint(Localization.component("map.search_items_by_name"));
        this.itemPickerSearchField.setMaxLength(48);
        this.itemPickerSearchField.setResponder(text -> {
            this.itemPickerSearch = text;
            filterWaypointItems();
        });
        this.itemPickerSearchField.setValue(this.itemPickerSearch);
        this.itemPickerSearchField.visible = false;
        this.addRenderableWidget(this.itemPickerSearchField);
        this.itemPickerClearButton = this.addRenderableWidget(Button.builder(Component.literal("X"), button -> {
            this.itemPickerSearchField.setValue("");
            this.itemPickerSearchField.setFocused(true);
            this.setFocused(this.itemPickerSearchField);
        }).bounds(pickerX + 260, pickerY + 25, 22, 20).build());
        this.itemPickerClearButton.visible = false;
        setWaypointItemPickerOpen(this.itemPickerOpen);
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        String currentDim = activeDimension();
        if (this.netherViewButton != null) {
            boolean supportsLayers = isNetherStyleDimension(currentDim);
            this.netherViewButton.setMessage(netherViewLabel(currentDim));
            this.netherViewButton.visible = supportsLayers && !this.itemPickerOpen;
            this.netherViewButton.active = supportsLayers;
        }
        if (this.dimensionButton != null) {
            this.dimensionButton.setMessage(dimensionButtonLabel());
            this.dimensionButton.visible = !this.itemPickerOpen;
        }
        if (this.settingsButton != null) this.settingsButton.visible = !this.itemPickerOpen;
        if (this.waypointSearchField != null) this.waypointSearchField.visible = !this.itemPickerOpen;
        if (this.itemSearchButton != null) this.itemSearchButton.visible = !this.itemPickerOpen;
        if (this.itemPickerSearchField != null) this.itemPickerSearchField.visible = this.itemPickerOpen;
        if (this.itemPickerClearButton != null) this.itemPickerClearButton.visible = this.itemPickerOpen;

        graphics.pose().pushMatrix();
        
        // Apply panning and scaling
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale((float) scale, (float) scale);
        graphics.pose().translate((float) panX, (float) panY);

        // Render map regions
        if (WorldMapConfig.showExploredAreas()) {
            String mapDimension = currentMapDimension(currentDim);
            Map<ChunkPos, ClientMapManager.RegionTexture> regions = ClientMapManager.getRegions(mapDimension);
            for (Map.Entry<ChunkPos, ClientMapManager.RegionTexture> entry : regions.entrySet()) {
                ChunkPos regionPos = entry.getKey();
                ClientMapManager.RegionTexture texture = entry.getValue();

                int worldX = regionPos.x() * 512;
                int worldZ = regionPos.z() * 512;
                if (!isRegionVisible(worldX, worldZ, centerX, centerY)) continue;

                Identifier textureLocation = texture.getTextureLocation();
                if (textureLocation != null) {
                    // Draw 512x512 region texture (id, x0, y0, x1, y1, u0, u1, v0, v1)
                    graphics.blit(textureLocation, worldX, worldZ, worldX + 512, worldZ + 512,
                        0.0f, 1.0f, 0.0f, 1.0f);
                }
            }
        }

        // Render waypoints (will be drawn in screen space later to prevent scaling)

        graphics.pose().popMatrix();

        if (this.itemPickerOpen) drawWaypointItemPickerBackground(graphics);

        // Keep controls above the map texture, but below waypoint and player markers.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (this.itemPickerOpen) {
            drawWaypointItemPickerContents(graphics, mouseX, mouseY);
            return;
        }

        // --- SCREEN SPACE RENDERING ---
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        drawPlayerNameSuggestion(graphics, mouseX, mouseY, currentDim);
        // Render waypoints in screen space
        com.runterya.worldmap.client.waypoint.Waypoint hoveredWaypoint = null;
        if (WorldMapConfig.showWaypoints()) {
            for (com.runterya.worldmap.client.waypoint.Waypoint wp : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
                if (!wp.getDimension().equals(currentDim)) continue;

                double screenX = centerX + (wp.getX() + panX) * scale;
                double screenY = centerY + (wp.getZ() + panY) * scale;

                boolean onScreen = isMapPositionVisible(screenX, screenY);
                if (!onScreen) {
                    double[] marker = markerScreenPosition(screenX, screenY, centerX, centerY);
                    drawWaypointMarker(graphics, wp, marker[0], marker[1]);
                    continue;
                }

                int sx = (int) Math.round(screenX);
                int sy = (int) Math.round(screenY);
                drawWaypointMarker(graphics, wp, sx, sy);

                // Keep hover details for the topmost layer so text remains legible.
                if (mouseX >= sx - 7 && mouseX <= sx + 7 && mouseY >= sy - 7 && mouseY <= sy + 7) {
                    hoveredWaypoint = wp;
                }
            }
        }

        // Keep the local position visible while filtering other players' explored areas.
        drawLocalPlayerMarker(graphics, centerX, centerY);
        // Draw remote player indicators after waypoints so they stay on top.
        if (WorldMapConfig.showPlayers()) drawOtherPlayerMarkers(graphics, font, centerX, centerY, currentDim);

        // Draw mouse coordinates
        double mouseWorldX = (mouseX - centerX) / scale - panX;
        double mouseWorldZ = (mouseY - centerY) / scale - panY;
        String coordText = Localization.text("map.coordinates", (int) Math.round(mouseWorldX), (int) Math.round(mouseWorldZ));
        graphics.fill(3, 3, font.width(coordText) + 8, font.lineHeight + 7, 0x99000000);
        graphics.text(font, coordText, 5, 5, 0xFFFFFFFF, true);

        if (hoveredWaypoint != null) {
            drawWaypointHoverTooltip(graphics, font, hoveredWaypoint, mouseX, mouseY);
        }

        drawWaypointSearchResults(graphics, mouseX, mouseY, currentDim);
        if (this.dimensionPickerOpen) drawDimensionPicker(graphics, mouseX, mouseY);
    }

    private String activeDimension() {
        return this.selectedDimension == null ? "minecraft:overworld" : this.selectedDimension;
    }

    void refreshLocalization() {
        if (this.settingsButton != null) this.settingsButton.setMessage(Localization.component("map.settings"));
        if (this.waypointSearchField != null) {
            this.waypointSearchField.setHint(Localization.component("map.search_waypoint_name"));
        }
        if (this.itemSearchButton != null) {
            this.itemSearchButton.setMessage(Localization.component(
                this.selectedSearchItem.isBlank() ? "map.choose_item" : "map.item_selected"));
        }
    }

    /** Skip off-screen map regions before requesting their texture upload. */
    private boolean isRegionVisible(double worldX, double worldZ, int centerX, int centerY) {
        double left = centerX + (worldX + panX) * scale;
        double top = centerY + (worldZ + panY) * scale;
        double right = left + 512.0 * scale;
        double bottom = top + 512.0 * scale;
        return right > 0 && bottom > 0 && left < this.width && top < this.height;
    }

    private Component dimensionButtonLabel() {
        return Localization.component("map.dimension", dimensionDisplayName(activeDimension()));
    }

    private static String dimensionDisplayName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> Localization.text("dimension.overworld");
            case "minecraft:the_nether" -> Localization.text("dimension.nether");
            case "minecraft:the_end" -> Localization.text("dimension.end");
            default -> dimension;
        };
    }

    private static boolean isNetherStyleDimension(String dimension) {
        return LayeredDimensions.contains(dimension);
    }

    private List<String> availableDimensions() {
        LinkedHashSet<String> dimensions = new LinkedHashSet<>(List.of(
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
        ));
        dimensions.addAll(ClientMapManager.getKnownDimensions().stream()
            .map(NetherMapView::gameDimension).toList());
        if (Minecraft.getInstance().level != null) {
            Minecraft minecraft = Minecraft.getInstance();
            dimensions.add(minecraft.level.dimension().identifier().toString());
            minecraft.level.registryAccess().lookup(Registries.LEVEL_STEM).ifPresent(levelStems ->
                dimensions.addAll(levelStems.keySet().stream().map(Object::toString).toList())
            );
        }
        dimensions.add(activeDimension());
        for (var waypoint : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
            dimensions.add(NetherMapView.gameDimension(waypoint.getDimension()));
        }
        dimensions.removeIf(String::isBlank);
        List<String> ordered = new ArrayList<>(dimensions);
        List<String> vanilla = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        ordered.subList(Math.min(vanilla.size(), ordered.size()), ordered.size()).sort(String::compareTo);
        return ordered;
    }

    private int dimensionPickerVisibleRows() {
        return Math.min(8, Math.max(1, (this.height - 48) / 20));
    }

    private int dimensionPickerX() {
        return Math.max(8, this.width - 208);
    }

    private int dimensionPickerY() {
        return Math.max(8, this.height - 34 - dimensionPickerVisibleRows() * 20);
    }

    private void drawDimensionPicker(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<String> dimensions = availableDimensions();
        int rows = dimensionPickerVisibleRows();
        int x = dimensionPickerX();
        int y = dimensionPickerY();
        graphics.fill(x, y, x + 200, y + rows * 20 + 4, 0xE0181A20);
        graphics.outline(x, y, 200, rows * 20 + 4, 0xFF777777);
        for (int row = 0; row < rows; row++) {
            int index = this.dimensionPickerOffset + row;
            if (index >= dimensions.size()) break;
            String dimension = dimensions.get(index);
            int rowY = y + 2 + row * 20;
            boolean hovered = mouseX >= x + 2 && mouseX < x + 198 && mouseY >= rowY && mouseY < rowY + 20;
            if (dimension.equals(activeDimension())) {
                graphics.fill(x + 2, rowY, x + 198, rowY + 20, 0xFF45454F);
            } else if (hovered) {
                graphics.fill(x + 2, rowY, x + 198, rowY + 20, 0xFF35353D);
            }
            String label = dimensionDisplayName(dimension);
            int maxWidth = 184;
            while (this.font.width(label) > maxWidth && label.length() > 4) {
                label = label.substring(0, label.length() - 4) + "…";
            }
            graphics.text(this.font, label, x + 8, rowY + 5,
                dimension.equals(activeDimension()) ? 0xFFFFFFFF : 0xFFCCCCCC);
        }
    }

    private boolean handleDimensionPickerClick(MouseButtonEvent event) {
        if (!this.dimensionPickerOpen) return false;
        int x = dimensionPickerX();
        int y = dimensionPickerY();
        int rows = dimensionPickerVisibleRows();
        boolean insidePicker = event.x() >= x && event.x() < x + 200
            && event.y() >= y && event.y() < y + rows * 20 + 4;
        if (!insidePicker) {
            int buttonX = Math.max(8, this.width - 190);
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() >= buttonX
                && event.x() < buttonX + 182 && event.y() >= this.height - 28 && event.y() < this.height - 8) {
                return false;
            }
            this.dimensionPickerOpen = false;
            return false;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        int row = (int) (event.y() - y - 2) / 20;
        int index = this.dimensionPickerOffset + row;
        List<String> dimensions = availableDimensions();
        if (row >= 0 && row < rows && index < dimensions.size()) {
            this.selectedDimension = dimensions.get(index);
            this.searchScrollOffset = 0;
        }
        this.dimensionPickerOpen = false;
        return true;
    }

    private static Component netherViewLabel(String dimension) {
        if (WorldMapConfig.netherMapView() == NetherMapView.BEDROCK_SURFACE) {
            return Localization.component(NetherMapView.NETHER_DIMENSION.equals(dimension)
                ? "map.nether_bedrock_top" : "map.layered_surface");
        }
        int layerY = selectedLayerYForDimension(dimension);
        return Localization.component(NetherMapView.NETHER_DIMENSION.equals(dimension)
            ? "map.nether_layer_y" : "map.layered_layer_y", layerY);
    }

    /** Auto follows the player's height in their current dimension. When browsing another
     * layered dimension, use the highest slice that already contains map data. */
    private static int selectedLayerYForDimension(String dimension) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean playerInSelectedDimension = minecraft.level != null && minecraft.player != null
            && minecraft.level.dimension().identifier().toString().equals(dimension);
        int minY = playerInSelectedDimension ? minecraft.level.getMinY() : -64;
        int maxY = playerInSelectedDimension ? minecraft.level.getMaxY() : 256;

        if (WorldMapConfig.isNetherLayerAuto() && !playerInSelectedDimension) {
            int highestAllowedLayer = LayeredDimensions.getMaxLayerY(dimension, minY, maxY);
            int highestMappedLayer = ClientMapManager.getKnownDimensions().stream()
                .filter(NetherMapView::isCaveLayerDimension)
                .filter(storageDimension -> NetherMapView.gameDimension(storageDimension).equals(dimension))
                .filter(storageDimension -> !ClientMapManager.getRegions(storageDimension).isEmpty())
                .mapToInt(NetherMapView::getCaveLayerY)
                .filter(layerY -> layerY != Integer.MIN_VALUE && layerY <= highestAllowedLayer)
                .max()
                .orElse(Integer.MIN_VALUE);
            if (highestMappedLayer != Integer.MIN_VALUE) return highestMappedLayer;
            return highestAllowedLayer;
        }

        int playerY = playerInSelectedDimension ? minecraft.player.blockPosition().getY() : 40;
        return WorldMapConfig.selectedNetherLayerY(dimension, minY, maxY, playerY);
    }

    private static String currentMapDimension(String currentDimension) {
        NetherMapView view = WorldMapConfig.netherMapView();
        if (view == NetherMapView.CAVE_LAYER && isNetherStyleDimension(currentDimension)) {
            int layerY = selectedLayerYForDimension(currentDimension);
            return view.storageDimension(currentDimension, layerY);
        }
        // The cave-layer preference only applies to dimensions explicitly opted in.
        // Keep ordinary dimensions on their normal map instead of looking up an
        // empty synthetic cave-layer cache (which made Overworld/End appear blank).
        return currentDimension;
    }

    private List<com.runterya.worldmap.network.PlayerPosPayload.PlayerPos> getPlayersInDimension(String currentDim) {
        Map<UUID, com.runterya.worldmap.network.PlayerPosPayload.PlayerPos> players = new LinkedHashMap<>();
        for (var player : ClientMapManager.getOtherPlayers()) {
            if (player.dimension().equals(currentDim)) players.put(player.uuid(), player);
        }
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null && Minecraft.getInstance().level != null
            && Minecraft.getInstance().level.dimension().identifier().toString().equals(currentDim)) {
            players.put(localPlayer.getUUID(), new com.runterya.worldmap.network.PlayerPosPayload.PlayerPos(
                localPlayer.getUUID(), localPlayer.getX(), localPlayer.getZ(), localPlayer.getYRot(),
                localPlayer.getName().getString(), currentDim));
        }
        return List.copyOf(players.values());
    }

    private String getPlayerNameSuggestion(String currentDim) {
        if (this.waypointSearchField == null) return null;
        String query = this.waypointSearchField.getValue().trim();
        if (query.isEmpty()) return null;

        LinkedHashSet<String> knownPlayerNames = new LinkedHashSet<>();
        getPlayersInDimension(currentDim).stream()
            .map(com.runterya.worldmap.network.PlayerPosPayload.PlayerPos::name)
            .filter(name -> name != null && !name.isBlank())
            .forEach(knownPlayerNames::add);
        ClientMapManager.getOtherPlayers().stream()
            .map(com.runterya.worldmap.network.PlayerPosPayload.PlayerPos::name)
            .filter(name -> name != null && !name.isBlank())
            .forEach(knownPlayerNames::add);
        com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints().stream()
            .map(com.runterya.worldmap.client.waypoint.Waypoint::getCreatorName)
            .filter(name -> name != null && !name.isBlank())
            .forEach(knownPlayerNames::add);

        return knownPlayerNames.stream()
            .filter(name -> !name.equalsIgnoreCase(query))
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(query.toLowerCase(Locale.ROOT)))
            .findFirst()
            .orElse(null);
    }

    private void drawPlayerNameSuggestion(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                          int mouseX, int mouseY, String currentDim) {
        String suggestion = getPlayerNameSuggestion(currentDim);
        if (suggestion == null) return;

        int x = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
        int y = SEARCH_RESULTS_TOP;
        int suggestionWidth = Math.min(190, this.width - 110);
        boolean hovered = mouseX >= x && mouseX < x + suggestionWidth
            && mouseY >= y && mouseY < y + PLAYER_SUGGESTION_HEIGHT;
        graphics.fill(x, y, x + suggestionWidth, y + PLAYER_SUGGESTION_HEIGHT,
            hovered ? 0xFF45454F : 0xF0202020);
        graphics.outline(x, y, suggestionWidth, PLAYER_SUGGESTION_HEIGHT, 0xFF777777);
        graphics.text(this.font, Localization.text("map.player_suggestion", suggestion), x + 6, y + 6, 0xFFFFFFFF, true);
    }

    private boolean handlePlayerNameSuggestionClick(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || this.waypointSearchField == null) return false;
        String currentDim = activeDimension();
        String suggestion = getPlayerNameSuggestion(currentDim);
        if (suggestion == null) return false;

        int x = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
        int suggestionWidth = Math.min(190, this.width - 110);
        if (event.x() < x || event.x() >= x + suggestionWidth
            || event.y() < SEARCH_RESULTS_TOP
            || event.y() >= SEARCH_RESULTS_TOP + PLAYER_SUGGESTION_HEIGHT) return false;

        this.waypointSearchField.setValue(suggestion);
        return true;
    }

    private int searchResultsPanelY(String currentDim) {
        return SEARCH_RESULTS_TOP
            + (getPlayerNameSuggestion(currentDim) == null ? 0 : PLAYER_SUGGESTION_HEIGHT);
    }

    private List<WaypointSearchResult> getWaypointSearchResults(String currentDim) {
        if (this.waypointSearchField == null) return List.of();
        String query = this.waypointSearchField.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty() && this.selectedSearchItem.isBlank()) return List.of();

        List<com.runterya.worldmap.network.PlayerPosPayload.PlayerPos> playerMatches = query.isEmpty()
            ? List.of()
            : getPlayersInDimension(currentDim).stream()
                .filter(player -> player.name().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        var localPlayer = Minecraft.getInstance().player;
        boolean searchingForLocalPlayer = !query.isEmpty() && localPlayer != null
            && localPlayer.getName().getString().toLowerCase(Locale.ROOT).contains(query);

        List<WaypointSearchResult> results = new ArrayList<>();
        com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints().stream()
            .filter(wp -> wp.getDimension().equals(currentDim))
            .filter(wp -> this.selectedSearchItem.isBlank() || wp.getIcon().equals(this.selectedSearchItem))
            .filter(wp -> query.isEmpty() || wp.getName().toLowerCase(Locale.ROOT).contains(query)
                || wp.getCategory().toLowerCase(Locale.ROOT).contains(query)
                || wp.getNote().toLowerCase(Locale.ROOT).contains(query)
                || waypointOwnerMatches(wp, query, playerMatches, searchingForLocalPlayer))
            .forEach(wp -> results.add(new WaypointSearchResult(wp, null)));

        if (this.selectedSearchItem.isBlank()) {
            for (var player : playerMatches) results.add(new WaypointSearchResult(null, player));
        }
        return List.copyOf(results);
    }

    private static boolean waypointOwnerMatches(
        com.runterya.worldmap.client.waypoint.Waypoint waypoint, String query,
        List<com.runterya.worldmap.network.PlayerPosPayload.PlayerPos> matchingPlayers,
        boolean searchingForLocalPlayer
    ) {
        if (waypoint.getCreatorName().toLowerCase(Locale.ROOT).contains(query)) return true;
        String creatorUuid = waypoint.getCreatorUuid();
        if (!creatorUuid.isEmpty() && matchingPlayers.stream()
            .anyMatch(player -> player.uuid().toString().equals(creatorUuid))) return true;
        return searchingForLocalPlayer && !waypoint.isGlobal();
    }

    private void openWaypointItemPicker() {
        List<SearchItemEntry> items = new ArrayList<>();
        BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).forEach(item -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                ItemStack stack = new ItemStack(item);
                items.add(new SearchItemEntry(id, stack,
                    (stack.getHoverName().getString() + " " + id).toLowerCase(Locale.ROOT)));
            }
        });
        this.allSearchItems = List.copyOf(items);
        this.filteredSearchItems = this.allSearchItems;
        this.itemPickerSearch = "";
        this.itemPickerScrollRow = 0;
        if (this.itemPickerSearchField != null) this.itemPickerSearchField.setValue("");
        setWaypointItemPickerOpen(true);
    }

    private void setWaypointItemPickerOpen(boolean open) {
        this.itemPickerOpen = open;
        if (this.itemPickerSearchField != null) {
            this.itemPickerSearchField.visible = open;
            this.itemPickerSearchField.active = open;
            if (open) {
                this.itemPickerSearchField.setFocused(true);
                this.setFocused(this.itemPickerSearchField);
            } else {
                this.itemPickerSearchField.setFocused(false);
                if (this.waypointSearchField != null) {
                    this.waypointSearchField.setFocused(false);
                    this.setFocused(this.waypointSearchField);
                }
            }
        }
        if (this.itemPickerClearButton != null) this.itemPickerClearButton.visible = open;
        if (this.settingsButton != null) this.settingsButton.visible = !open;
        if (this.netherViewButton != null) {
            this.netherViewButton.visible = !open && isNetherStyleDimension(activeDimension());
        }
        if (this.dimensionButton != null) this.dimensionButton.visible = !open;
        if (this.waypointSearchField != null) this.waypointSearchField.visible = !open;
        if (this.itemSearchButton != null) this.itemSearchButton.visible = !open;
    }

    private void filterWaypointItems() {
        String query = this.itemPickerSearch.trim().toLowerCase(Locale.ROOT);
        this.filteredSearchItems = this.allSearchItems.stream()
            .filter(item -> query.isEmpty() || item.searchName().contains(query)).toList();
        this.itemPickerScrollRow = 0;
    }

    private void selectWaypointSearchItem(int filteredIndex) {
        if (filteredIndex < 0 || filteredIndex >= this.filteredSearchItems.size()) return;
        String selected = this.filteredSearchItems.get(filteredIndex).id().toString();
        this.selectedSearchItem = selected.equals(this.selectedSearchItem) ? "" : selected;
        if (this.waypointSearchField != null) this.waypointSearchField.setValue("");
        if (this.itemSearchButton != null) {
            this.itemSearchButton.setMessage(Localization.component(
                this.selectedSearchItem.isBlank() ? "map.choose_item" : "map.item_selected"));
        }
        this.searchScrollOffset = 0;
        setWaypointItemPickerOpen(false);
    }

    private void drawWaypointSearchResults(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                           int mouseX, int mouseY, String currentDim) {
        List<WaypointSearchResult> results = getWaypointSearchResults(currentDim);
        if (results.isEmpty()) return;

        int panelX = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
        int panelY = searchResultsPanelY(currentDim);
        int headerHeight = 18;
        int visibleRows = Math.min(MAX_SEARCH_RESULTS,
            Math.max(1, (this.height - panelY - headerHeight - 36) / SEARCH_ROW_HEIGHT));
        int maxOffset = Math.max(0, results.size() - visibleRows);
        this.searchScrollOffset = Math.max(0, Math.min(maxOffset, this.searchScrollOffset));
        int shownRows = Math.min(visibleRows, results.size() - this.searchScrollOffset);
        int panelHeight = headerHeight + shownRows * SEARCH_ROW_HEIGHT + 2;
        graphics.fill(panelX, panelY, panelX + SEARCH_PANEL_WIDTH, panelY + panelHeight, 0xF0202020);
        graphics.outline(panelX, panelY, SEARCH_PANEL_WIDTH, panelHeight, 0xFF777777);
        String header = this.selectedSearchItem.isBlank()
            ? Localization.text("map.search_results_players", results.size())
            : Localization.text("map.search_results_waypoints", selectedSearchItemName(), results.size());
        int headerMaxWidth = SEARCH_PANEL_WIDTH - 12;
        while (!header.isEmpty() && this.font.width(header) > headerMaxWidth) {
            header = header.substring(0, header.length() - 1);
        }
        graphics.text(this.font, header, panelX + 6, panelY + 5, 0xFFCCCCCC, true);

        for (int row = 0; row < shownRows; row++) {
            int index = this.searchScrollOffset + row;
            WaypointSearchResult result = results.get(index);
            int rowY = panelY + headerHeight + row * SEARCH_ROW_HEIGHT;
            boolean hovered = mouseX >= panelX && mouseX < panelX + SEARCH_PANEL_WIDTH
                && mouseY >= rowY && mouseY < rowY + SEARCH_ROW_HEIGHT;
            if (hovered) graphics.fill(panelX + 1, rowY, panelX + SEARCH_PANEL_WIDTH - 1,
                rowY + SEARCH_ROW_HEIGHT, 0xFF45454F);

            String label;
            if (result.player() != null) {
                var player = result.player();
                int playerColor = Minecraft.getInstance().player != null
                    && player.uuid().equals(Minecraft.getInstance().player.getUUID())
                    ? 0xFFFFFFFF : colorForPlayer(player.uuid());
                graphics.blit(RenderPipelines.GUI_TEXTURED, PLAYER_MARKER,
                    panelX + 4, rowY + 4, 0.0f, 0.0f, 14, 14, 8, 8, 8, 8, playerColor);
                label = player.name() + "  " + (int) Math.round(player.x()) + ", " + (int) Math.round(player.z());
            } else {
                var waypoint = result.waypoint();
                if (waypoint.getIcon().isBlank()) {
                    graphics.fill(panelX + 5, rowY + 5, panelX + 19, rowY + 19, 0xFF000000);
                    graphics.fill(panelX + 6, rowY + 6, panelX + 18, rowY + 18,
                        waypoint.getColor() | 0xFF000000);
                } else {
                    var iconId = net.minecraft.resources.Identifier.tryParse(waypoint.getIcon());
                    var item = iconId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(iconId);
                    if (item == Items.AIR) {
                        graphics.fill(panelX + 5, rowY + 5, panelX + 19, rowY + 19,
                            waypoint.getColor() | 0xFF000000);
                    } else {
                        graphics.item(new ItemStack(item), panelX + 4, rowY + 3);
                    }
                }
                label = waypoint.getName()
                    + (waypoint.getCategory().isBlank() ? "" : " · " + waypoint.getCategory())
                    + "  " + waypoint.getX() + ", " + waypoint.getZ();
            }

            int maxTextWidth = SEARCH_PANEL_WIDTH - 30;
            while (!label.isEmpty() && this.font.width(label) > maxTextWidth) {
                label = label.substring(0, label.length() - 1);
            }
            graphics.text(this.font, label, panelX + 24, rowY + 6, 0xFFFFFFFF, true);
        }
    }

    private String selectedSearchItemName() {
        Identifier id = Identifier.tryParse(this.selectedSearchItem);
        if (id == null) return Localization.text("map.selected_item");
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? this.selectedSearchItem : new ItemStack(item).getHoverName().getString();
    }

    private void drawWaypointItemPickerBackground(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
        graphics.fill(panelX, panelY, panelX + 290, panelY + 238, 0xFF202020);
        graphics.outline(panelX, panelY, 290, 238, 0xFFAAAAAA);
        graphics.centeredText(this.font, Localization.text("map.choose_item_for_waypoints"), panelX + 145, panelY + 8, 0xFFFFFFFF);
    }

    private void drawWaypointItemPickerContents(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                                int mouseX, int mouseY) {
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
        int firstIndex = this.itemPickerScrollRow * ITEM_PICKER_COLUMNS;
        int visibleCount = ITEM_PICKER_COLUMNS * ITEM_PICKER_ROWS;
        int count = Math.min(visibleCount, this.filteredSearchItems.size() - firstIndex);
        for (int i = 0; i < count; i++) {
            SearchItemEntry entry = this.filteredSearchItems.get(firstIndex + i);
            int column = i % ITEM_PICKER_COLUMNS;
            int row = i / ITEM_PICKER_COLUMNS;
            int x = panelX + 18 + column * 28;
            int y = panelY + 54 + row * 28;
            boolean hovered = mouseX >= x && mouseX < x + 24 && mouseY >= y && mouseY < y + 24;
            boolean selected = entry.id().toString().equals(this.selectedSearchItem);
            graphics.fill(x, y, x + 24, y + 24, selected ? 0xFF75662E : hovered ? 0xFF555555 : 0xFF333333);
            graphics.item(entry.stack(), x + 4, y + 4);
            if (hovered) graphics.setTooltipForNextFrame(this.font, entry.stack(), mouseX, mouseY);
        }
        graphics.text(this.font, Localization.text("map.item_count", this.filteredSearchItems.size()),
            panelX + 12, panelY + 224, 0xFFCCCCCC);
    }

    private boolean handleWaypointItemPickerClick(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
        int gridX = panelX + 18;
        int gridY = panelY + 54;
        if (event.x() >= gridX && event.x() < gridX + ITEM_PICKER_COLUMNS * 28
            && event.y() >= gridY && event.y() < gridY + ITEM_PICKER_ROWS * 28) {
            int column = (int) (event.x() - gridX) / 28;
            int row = (int) (event.y() - gridY) / 28;
            selectWaypointSearchItem((this.itemPickerScrollRow + row) * ITEM_PICKER_COLUMNS + column);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (!this.itemPickerOpen && event.key() == InputConstants.KEY_TAB
            && this.waypointSearchField != null && this.waypointSearchField.isFocused()) {
            String currentDim = activeDimension();
            String suggestion = getPlayerNameSuggestion(currentDim);
            if (suggestion != null) {
                this.waypointSearchField.setValue(suggestion);
                return true;
            }
        }
        if (!this.itemPickerOpen) return super.keyPressed(event);
        if (event.key() == InputConstants.KEY_ESCAPE) {
            setWaypointItemPickerOpen(false);
            return true;
        }
        if (event.key() == InputConstants.KEY_RETURN) {
            selectWaypointSearchItem(0);
            return true;
        }
        super.keyPressed(event);
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!this.itemPickerOpen) return super.charTyped(event);
        super.charTyped(event);
        return true;
    }

    private void drawLocalPlayerMarker(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                       int centerX, int centerY) {
        var player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().level == null) return;
        double[] mapPosition = getLocalPlayerPositionInActiveDimension();
        if (mapPosition == null) return;
        double playerX = centerX + (mapPosition[0] + panX) * scale;
        double playerY = centerY + (mapPosition[1] + panY) * scale;
        double[] marker = markerScreenPosition(playerX, playerY, centerX, centerY);
        boolean onScreen = playerX >= 10 && playerX <= width - 10
            && playerY >= 10 && playerY <= height - 24;
        float rotation = onScreen
            ? player.getYRot() + 180.0f
            : (float) Math.toDegrees(Math.atan2(playerX - centerX, -(playerY - centerY)));
        drawPlayerArrow(graphics, marker[0], marker[1], rotation, 0xFFFFFFFF);
    }

    /** Projects the local player's coordinates into the currently viewed dimension. */
    private double[] getLocalPlayerPositionInActiveDimension() {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null) return new double[] {0.0, 0.0};

        String playerDimension = level.dimension().identifier().toString();
        String mapDimension = activeDimension();
        if (playerDimension.equals(mapDimension)) return new double[] {player.getX(), player.getZ()};

        double playerScale = dimensionCoordinateScale(playerDimension);
        double mapScale = dimensionCoordinateScale(mapDimension);
        // Equal coordinate scales do not imply that two dimensions share coordinates
        // (for example, the End has scale 1 like the Overworld). Only project between
        // dimensions when their dimension types define a real scale conversion.
        if (Math.abs(playerScale - mapScale) < 1.0E-9) return null;
        double ratio = playerScale / mapScale;
        return new double[] {player.getX() * ratio, player.getZ() * ratio};
    }

    /** Uses each dimension type's vanilla coordinate scale when available. */
    private static double dimensionCoordinateScale(String dimension) {
        Minecraft minecraft = Minecraft.getInstance();
        Identifier id = Identifier.tryParse(dimension);
        if (id != null && minecraft.level != null) {
            var levelStems = minecraft.level.registryAccess().lookup(Registries.LEVEL_STEM).orElse(null);
            if (levelStems != null) {
                var stem = levelStems.getValue(id);
                if (stem != null) {
                    double coordinateScale = stem.type().value().coordinateScale();
                    if (Double.isFinite(coordinateScale) && coordinateScale > 0.0) return coordinateScale;
                }
            }
        }
        // Keep vanilla Overworld/Nether behavior correct if a registry entry is unavailable.
        return NetherMapView.NETHER_DIMENSION.equals(dimension) ? 8.0 : 1.0;
    }

    private void drawOtherPlayerMarkers(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                        net.minecraft.client.gui.Font font, int centerX, int centerY,
                                        String currentDim) {
        for (com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player : ClientMapManager.getOtherPlayers()) {
            if (!player.dimension().equals(currentDim)) continue;
            if (Minecraft.getInstance().player != null && player.uuid().equals(Minecraft.getInstance().player.getUUID())) {
                continue;
            }
            drawOtherPlayer(graphics, font, player, centerX, centerY);
        }
    }

    private void drawWaypointMarker(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                    com.runterya.worldmap.client.waypoint.Waypoint wp,
                                    double screenX, double screenY) {
        int sx = (int) Math.round(screenX);
        int sy = (int) Math.round(screenY);
        if (wp.getIcon().isBlank()) {
            // Keep the compact colored square as the default marker.
            graphics.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFF000000);
            graphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, wp.getColor() | 0xFF000000);
        } else {
            graphics.fill(sx - 5, sy - 5, sx + 5, sy + 5, 0xFF000000);
            graphics.fill(sx - 4, sy - 4, sx + 4, sy + 4, wp.getColor() | 0xFF000000);
            var iconId = net.minecraft.resources.Identifier.tryParse(wp.getIcon());
            var item = iconId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(iconId);
            if (item == Items.AIR) {
                graphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFFFFF);
            } else {
                graphics.pose().pushMatrix();
                graphics.pose().translate(sx - 4, sy - 4);
                graphics.pose().scale(0.5f, 0.5f);
                graphics.item(new ItemStack(item), 0, 0);
                graphics.pose().popMatrix();
            }
        }
    }

    private void drawWaypointHoverTooltip(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                          net.minecraft.client.gui.Font font,
                                          com.runterya.worldmap.client.waypoint.Waypoint waypoint,
                                          int mouseX, int mouseY) {
        int maxTextWidth = Math.min(240, Math.max(80, this.width - 16));
        List<String> lines = new ArrayList<>();
        lines.add(waypoint.getName());
        if (!waypoint.getCategory().isBlank()) {
            lines.add(Localization.text("waypoint_menu.category", waypoint.getCategory()));
        }
        if (!waypoint.getNote().isBlank()) {
            lines.addAll(wrapTooltipText(
                Localization.text("waypoint_menu.note", waypoint.getNote()), font, maxTextWidth));
        }

        int textWidth = lines.stream().mapToInt(font::width).max().orElse(0);
        int boxWidth = Math.min(maxTextWidth + 8, textWidth + 8);
        int boxHeight = lines.size() * (font.lineHeight + 2) + 6;
        int boxX = mouseX + 12;
        int boxY = mouseY + 12;
        if (boxX + boxWidth > this.width - 4) boxX = mouseX - boxWidth - 12;
        if (boxY + boxHeight > this.height - 4) boxY = mouseY - boxHeight - 12;
        boxX = Math.max(4, boxX);
        boxY = Math.max(4, boxY);

        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0101010);
        graphics.outline(boxX, boxY, boxWidth, boxHeight, 0xFF777777);
        int textY = boxY + 4;
        for (int index = 0; index < lines.size(); index++) {
            graphics.text(font, lines.get(index), boxX + 4, textY,
                index == 0 ? 0xFFFFFFFF : 0xFFD0D0D0);
            textY += font.lineHeight + 2;
        }
    }

    private static List<String> wrapTooltipText(String text, net.minecraft.client.gui.Font font, int maxWidth) {
        List<String> wrapped = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (font.width(word) > maxWidth) {
                if (!line.isEmpty()) {
                    wrapped.add(line.toString());
                    line.setLength(0);
                }
                StringBuilder segment = new StringBuilder();
                for (int index = 0; index < word.length(); index++) {
                    char character = word.charAt(index);
                    if (!segment.isEmpty() && font.width(segment.toString() + character) > maxWidth) {
                        wrapped.add(segment.toString());
                        segment.setLength(0);
                    }
                    segment.append(character);
                }
                if (!segment.isEmpty()) line.append(segment);
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                wrapped.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
        }
        if (!line.isEmpty()) wrapped.add(line.toString());
        return wrapped;
    }

    private void drawOtherPlayer(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                 net.minecraft.client.gui.Font font,
                                 com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player,
                                 int centerX, int centerY) {
        double screenX = centerX + (player.x() + panX) * scale;
        double screenY = centerY + (player.z() + panY) * scale;
        double dx = screenX - centerX;
        double dy = screenY - centerY;
        int color = colorForPlayer(player.uuid());
        boolean onScreen = screenX >= 10 && screenX <= width - 10
            && screenY >= 10 && screenY <= height - 24;
        double[] markerPosition = playerMarkerPosition(player, centerX, centerY);

        if (onScreen) {
            drawPlayerArrow(graphics, markerPosition[0], markerPosition[1], player.yaw() + 180.0f, color);
        } else {
            float towardPlayer = (float) Math.toDegrees(Math.atan2(dx, -dy));
            drawPlayerArrow(graphics, markerPosition[0], markerPosition[1], towardPlayer, color);
        }

        drawPlayerName(graphics, font, player.name(), markerPosition[0], markerPosition[1]);
    }

    private double[] playerMarkerPosition(
        com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player, int centerX, int centerY
    ) {
        double screenX = centerX + (player.x() + panX) * scale;
        double screenY = centerY + (player.z() + panY) * scale;
        return markerScreenPosition(screenX, screenY, centerX, centerY);
    }

    private double[] markerScreenPosition(double screenX, double screenY, int centerX, int centerY) {
        if (screenX >= 10 && screenX <= width - 10 && screenY >= 10 && screenY <= height - 24) {
            return new double[] {screenX, screenY};
        }

        double dx = screenX - centerX;
        double dy = screenY - centerY;
        double halfWidth = Math.max(1, width / 2.0 - 16);
        double halfHeight = Math.max(1, height / 2.0 - 28);
        double scaleToEdge = Math.min(
            dx == 0 ? Double.POSITIVE_INFINITY : halfWidth / Math.abs(dx),
            dy == 0 ? Double.POSITIVE_INFINITY : halfHeight / Math.abs(dy)
        );
        return new double[] {centerX + dx * scaleToEdge, centerY + dy * scaleToEdge};
    }

    private void drawPlayerArrow(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                 double screenX, double screenY, float rotationDegrees, int color) {
        if (screenX < -10 || screenX > width + 10 || screenY < -10 || screenY > height + 10) return;

        graphics.pose().pushMatrix();
        graphics.pose().translate((float) screenX, (float) screenY);
        // The arrow points north before rotation; Minecraft yaw 0 faces south.
        graphics.pose().rotate((float) Math.toRadians(rotationDegrees));

        // Vanilla map player-decoration texture, kept screen-sized and tinted per player.
        graphics.blit(RenderPipelines.GUI_TEXTURED, PLAYER_MARKER,
            -7, -7, 0.0f, 0.0f, 14, 14, 8, 8, 8, 8, color);
        graphics.pose().popMatrix();
    }

    private void drawPlayerName(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                net.minecraft.client.gui.Font font, String name,
                                double markerX, double markerY) {
        int textWidth = font.width(name);
        int labelX = (int) Math.round(Math.max(textWidth / 2.0 + 2,
            Math.min(width - textWidth / 2.0 - 2, markerX)));
        int labelY = (int) Math.round(Math.min(height - font.lineHeight - 2, markerY + 10));
        graphics.fill(labelX - textWidth / 2 - 2, labelY - 1,
            labelX + textWidth / 2 + 2, labelY + font.lineHeight, 0x99000000);
        graphics.centeredText(font, name, labelX, labelY, 0xFFFFFFFF);
    }

    private static int colorForPlayer(UUID uuid) {
        // Match Minecraft's default Locator Bar color: Java's UUID hash folded to
        // 24-bit RGB, then normalized to 90% HSV brightness.
        int rgb = uuid.hashCode() & 0x00FFFFFF;
        float[] hsb = java.awt.Color.RGBtoHSB(
            (rgb >>> 16) & 0xFF,
            (rgb >>> 8) & 0xFF,
            rgb & 0xFF,
            null
        );
        return java.awt.Color.HSBtoRGB(hsb[0], hsb[1], 0.9f) | 0xFF000000;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            // Adjust pan based on drag and scale
            this.panX += dragX / this.scale;
            this.panY += dragY / this.scale;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDouble) {
        if (this.itemPickerOpen) {
            if (handleWaypointItemPickerClick(event)) return true;
            super.mouseClicked(event, isDouble);
            return true;
        }
        if (handleDimensionPickerClick(event)) return true;
        if (handlePlayerNameSuggestionClick(event)) return true;
        if (handleWaypointSearchResultClick(event)) return true;

        // Let GUI controls (such as the Settings button) handle their clicks first.
        if (super.mouseClicked(event, isDouble)) return true;

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            String currentDim = activeDimension();

            if (WorldMapConfig.showPlayers() && Minecraft.getInstance().player != null
                && Minecraft.getInstance().level != null) {
                double[] mapPosition = getLocalPlayerPositionInActiveDimension();
                if (mapPosition != null) {
                    double localX = centerX + (mapPosition[0] + panX) * scale;
                    double localY = centerY + (mapPosition[1] + panY) * scale;
                    double[] localMarker = markerScreenPosition(localX, localY, centerX, centerY);
                    if (isNearMarker(event.x(), event.y(), localMarker[0], localMarker[1])) {
                        centerMapOn(mapPosition[0], mapPosition[1]);
                        return true;
                    }
                }
            }

            if (WorldMapConfig.showPlayers()) {
                for (com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player : getPlayersInDimension(currentDim)) {
                    if (Minecraft.getInstance().player != null
                        && player.uuid().equals(Minecraft.getInstance().player.getUUID())) continue;
                    double[] marker = playerMarkerPosition(player, centerX, centerY);
                    if (isNearMarker(event.x(), event.y(), marker[0], marker[1])) {
                        centerMapOn(player.x(), player.z());
                        return true;
                    }
                }
            }

            if (WorldMapConfig.showWaypoints()) {
                String dim = currentDim;
                for (com.runterya.worldmap.client.waypoint.Waypoint wp
                    : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
                    if (!wp.getDimension().equals(dim)) continue;
                    double screenX = centerX + (wp.getX() + panX) * scale;
                    double screenY = centerY + (wp.getZ() + panY) * scale;
                    if (isMapPositionVisible(screenX, screenY)) continue;

                    double[] marker = markerScreenPosition(screenX, screenY, centerX, centerY);
                    if (isNearMarker(event.x(), event.y(), marker[0], marker[1])) {
                        centerMapOn(wp.getX(), wp.getZ());
                        return true;
                    }
                }
            }

            // Consume the left press so Screen keeps it captured for mouseDragged.
            return true;
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            String dim = activeDimension();

            // Player markers are drawn above waypoints, so give them right-click priority too.
            if (WorldMapConfig.showPlayers() && Minecraft.getInstance().player != null) {
                UUID localPlayerId = Minecraft.getInstance().player.getUUID();
                for (var player : getPlayersInDimension(dim)) {
                    if (player.uuid().equals(localPlayerId)) continue;
                    double[] marker = playerMarkerPosition(player, centerX, centerY);
                    if (isNearMarker(event.x(), event.y(), marker[0], marker[1])) {
                        com.runterya.worldmap.client.ClientPlatform.setScreen(
                            Minecraft.getInstance(), new PlayerContextMenuScreen(this, player)
                        );
                        return true;
                    }
                }
            }
            
            // Convert screen coordinates to world coordinates
            double worldX = (event.x() - centerX) / this.scale - this.panX;
            double worldZ = (event.y() - centerY) / this.scale - this.panY;
            
            int color = 0xFF000000 | new java.util.Random().nextInt(0xFFFFFF);
            
            // We use integer block coordinates, Y is estimated or set to 64
            int blockX = (int) Math.round(worldX);
            int blockZ = (int) Math.round(worldZ);
            
            // Hit-test waypoints in screen space so the target stays usable at every zoom level.
            com.runterya.worldmap.client.waypoint.Waypoint clickedWaypoint = null;
            if (WorldMapConfig.showWaypoints()) {
                for (com.runterya.worldmap.client.waypoint.Waypoint wp : com.runterya.worldmap.client.waypoint.WaypointManager.getWaypoints()) {
                    if (!wp.getDimension().equals(dim)) continue;
                    double waypointX = centerX + (wp.getX() + panX) * scale;
                    double waypointY = centerY + (wp.getZ() + panY) * scale;
                    if (Math.abs(waypointX - event.x()) <= 7 && Math.abs(waypointY - event.y()) <= 7) {
                        clickedWaypoint = wp;
                        break;
                    }
                }
            }
            
            if (clickedWaypoint != null) {
                com.runterya.worldmap.client.ClientPlatform.setScreen(Minecraft.getInstance(), new WaypointContextMenuScreen(this, clickedWaypoint));
            } else {
                int blockY = 64; // Default Y for a dimension other than the player's current one.
                if (Minecraft.getInstance().player != null && Minecraft.getInstance().level != null
                    && Minecraft.getInstance().level.dimension().identifier().toString().equals(dim)) {
                    blockY = Minecraft.getInstance().player.getBlockY();
                }
                
                com.runterya.worldmap.client.ClientPlatform.setScreen(Minecraft.getInstance(), new WaypointAddScreen(this, blockX, blockY, blockZ, dim));
            }
            return true;
        }
        return false;
    }

    private boolean handleWaypointSearchResultClick(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || this.waypointSearchField == null
            || (this.waypointSearchField.getValue().isBlank() && this.selectedSearchItem.isBlank())) {
            return false;
        }

        String currentDim = activeDimension();
        List<WaypointSearchResult> results = getWaypointSearchResults(currentDim);
        int panelX = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
        int panelY = searchResultsPanelY(currentDim);
        int headerHeight = 18;
        int visibleRows = Math.min(MAX_SEARCH_RESULTS,
            Math.max(1, (this.height - panelY - headerHeight - 36) / SEARCH_ROW_HEIGHT));
        int shownRows = Math.min(visibleRows, Math.max(0, results.size() - this.searchScrollOffset));
        if (event.x() < panelX || event.x() >= panelX + SEARCH_PANEL_WIDTH
            || event.y() < panelY + headerHeight
            || event.y() >= panelY + headerHeight + shownRows * SEARCH_ROW_HEIGHT) {
            return false;
        }

        int row = (int) (event.y() - panelY - headerHeight) / SEARCH_ROW_HEIGHT;
        int selectedIndex = this.searchScrollOffset + row;
        if (selectedIndex < 0 || selectedIndex >= results.size()) return true;

        WaypointSearchResult selected = results.get(selectedIndex);
        if (selected.player() != null) centerMapOn(selected.player().x(), selected.player().z());
        else centerMapOn(selected.waypoint().getX(), selected.waypoint().getZ());
        return true;
    }

    private static boolean isNearMarker(double mouseX, double mouseY, double markerX, double markerY) {
        double dx = mouseX - markerX;
        double dy = mouseY - markerY;
        return dx * dx + dy * dy <= 100;
    }

    private boolean isMapPositionVisible(double screenX, double screenY) {
        return screenX >= 10 && screenX <= width - 10
            && screenY >= 10 && screenY <= height - 24;
    }

    private void centerMapOn(double worldX, double worldZ) {
        panX = -worldX;
        panY = -worldZ;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.itemPickerOpen) {
            int rows = (this.filteredSearchItems.size() + ITEM_PICKER_COLUMNS - 1) / ITEM_PICKER_COLUMNS;
            int maxScroll = Math.max(0, rows - ITEM_PICKER_ROWS);
            this.itemPickerScrollRow = Math.max(0, Math.min(maxScroll,
                this.itemPickerScrollRow - (int) Math.signum(scrollY)));
            return true;
        }

        if (this.dimensionPickerOpen && mouseX >= dimensionPickerX() && mouseX < dimensionPickerX() + 200
            && mouseY >= dimensionPickerY() && mouseY < dimensionPickerY() + dimensionPickerVisibleRows() * 20 + 4) {
            int maxOffset = Math.max(0, availableDimensions().size() - dimensionPickerVisibleRows());
            this.dimensionPickerOffset = Math.max(0, Math.min(maxOffset,
                this.dimensionPickerOffset - (int) Math.signum(scrollY)));
            return true;
        }

        String currentDim = activeDimension();
        List<WaypointSearchResult> searchResults = getWaypointSearchResults(currentDim);
        int searchPanelX = Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8);
        int searchPanelY = searchResultsPanelY(currentDim);
        int searchVisibleRows = Math.min(MAX_SEARCH_RESULTS,
            Math.max(1, (this.height - searchPanelY - 36) / SEARCH_ROW_HEIGHT));
        int searchShownRows = Math.min(searchVisibleRows, searchResults.size());
        int searchHeaderHeight = 18;
        if (searchShownRows > 0 && mouseX >= searchPanelX && mouseX < searchPanelX + SEARCH_PANEL_WIDTH
            && mouseY >= searchPanelY + searchHeaderHeight
            && mouseY < searchPanelY + searchHeaderHeight + searchShownRows * SEARCH_ROW_HEIGHT) {
            int maxOffset = Math.max(0, searchResults.size() - searchVisibleRows);
            this.searchScrollOffset = Math.max(0, Math.min(maxOffset,
                this.searchScrollOffset - (int) Math.signum(scrollY)));
            return true;
        }

        if (scrollY > 0) {
            scale *= 1.2; // Zoom in
        } else if (scrollY < 0) {
            scale /= 1.2; // Zoom out
        }
        scale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause game in singleplayer while map is open
    }
}
