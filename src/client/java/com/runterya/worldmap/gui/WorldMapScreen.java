package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import com.runterya.worldmap.backend.NetherMapView;
import com.runterya.worldmap.client.ClientMapManager;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private Button netherViewButton;
    private boolean itemPickerOpen;
    private String itemPickerSearch = "";
    private int itemPickerScrollRow;
    private String selectedSearchItem = "";
    private List<SearchItemEntry> allSearchItems = List.of();
    private List<SearchItemEntry> filteredSearchItems = List.of();

    public WorldMapScreen() {
        super(Component.literal("World Map"));
        
        // Center the map on the local player if they exist
        if (Minecraft.getInstance().player != null) {
            this.panX = -Minecraft.getInstance().player.getX();
            this.panY = -Minecraft.getInstance().player.getZ();
        }
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Map settings"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(
                this.minecraft, new WorldMapConfigScreen(this)
            )
        ).bounds(8, this.height - 28, 100, 20).build());
        if (Minecraft.getInstance().level != null
            && NetherMapView.NETHER_DIMENSION.equals(Minecraft.getInstance().level.dimension().identifier().toString())) {
            this.netherViewButton = this.addRenderableWidget(Button.builder(netherViewLabel(), button -> {
                com.runterya.worldmap.client.ClientPlatform.setScreen(
                    this.minecraft, new NetherLayerSelectionScreen(this)
                );
            }).bounds(112, this.height - 28, 150, 20).build());
        }
        this.waypointSearchField = new EditBox(this.font,
            Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8), 8,
            Math.min(190, this.width - 110), 20,
            Component.literal("Search waypoints"));
        this.waypointSearchField.setHint(Component.literal("Search waypoint name"));
        this.waypointSearchField.setMaxLength(64);
        this.waypointSearchField.setResponder(text -> this.searchScrollOffset = 0);
        this.addRenderableWidget(this.waypointSearchField);
        this.itemSearchButton = this.addRenderableWidget(Button.builder(Component.literal(
            this.selectedSearchItem.isBlank() ? "Choose item" : "Item selected"), button ->
            openWaypointItemPicker()
        ).bounds(Math.max(8, this.width - SEARCH_PANEL_WIDTH - 8) + 196, 8,
            Math.min(76, Math.max(40, this.width - 224)), 20).build());
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        String currentDim = Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.dimension().identifier().toString()
            : "minecraft:overworld";
        if (this.netherViewButton != null) this.netherViewButton.setMessage(netherViewLabel());

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

                if (texture.getTextureLocation() != null) {
                    // Draw 512x512 region texture (id, x0, y0, x1, y1, u0, u1, v0, v1)
                    graphics.blit(texture.getTextureLocation(), worldX, worldZ, worldX + 512, worldZ + 512, 0.0f, 1.0f, 0.0f, 1.0f);
                }
            }
        }

        // Render waypoints (will be drawn in screen space later to prevent scaling)

        graphics.pose().popMatrix();

        // Keep controls above the map texture, but below waypoint and player markers.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // --- SCREEN SPACE RENDERING ---
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        drawPlayerNameSuggestion(graphics, mouseX, mouseY, currentDim);
        // Render waypoints in screen space
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

                // Draw name centered above if hovered
                if (mouseX >= sx - 7 && mouseX <= sx + 7 && mouseY >= sy - 7 && mouseY <= sy + 7) {
                    String name = wp.getName();
                    graphics.centeredText(font, name, sx, sy - 14, wp.getColor() | 0xFF000000);
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
        String coordText = String.format("X: %d, Z: %d", (int) Math.round(mouseWorldX), (int) Math.round(mouseWorldZ));
        graphics.fill(3, 3, font.width(coordText) + 8, font.lineHeight + 7, 0x99000000);
        graphics.text(font, coordText, 5, 5, 0xFFFFFFFF, true);

        drawWaypointSearchResults(graphics, mouseX, mouseY, currentDim);
        if (this.itemPickerOpen) drawWaypointItemPicker(graphics, mouseX, mouseY);
    }

    private static Component netherViewLabel() {
        if (WorldMapConfig.netherMapView() == NetherMapView.BEDROCK_SURFACE) {
            return Component.literal("Nether: Bedrock top");
        }
        Minecraft minecraft = Minecraft.getInstance();
        int layerY = minecraft.level == null || minecraft.player == null ? 40
            : WorldMapConfig.selectedNetherLayerY(minecraft.level.getMinY(), minecraft.level.getMaxY(),
                minecraft.player.blockPosition().getY());
        return Component.literal("Nether: Cave layer Y " + layerY);
    }

    private static String currentMapDimension(String currentDimension) {
        NetherMapView view = WorldMapConfig.netherMapView();
        if (view == NetherMapView.CAVE_LAYER && Minecraft.getInstance().level != null
            && Minecraft.getInstance().player != null
            && NetherMapView.NETHER_DIMENSION.equals(currentDimension)) {
            int layerY = WorldMapConfig.selectedNetherLayerY(
                Minecraft.getInstance().level.getMinY(), Minecraft.getInstance().level.getMaxY(),
                Minecraft.getInstance().player.blockPosition().getY());
            return view.storageDimension(currentDimension, layerY);
        }
        return view.storageDimension(currentDimension);
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
        graphics.text(this.font, suggestion + "?", x + 6, y + 6, 0xFFFFFFFF, true);
    }

    private boolean handlePlayerNameSuggestionClick(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || this.waypointSearchField == null) return false;
        String currentDim = Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.dimension().identifier().toString()
            : "minecraft:overworld";
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
        this.itemPickerOpen = true;
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
            this.itemSearchButton.setMessage(Component.literal(
                this.selectedSearchItem.isBlank() ? "Choose item" : "Item selected"));
        }
        this.searchScrollOffset = 0;
        this.itemPickerOpen = false;
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
            ? results.size() + " matching waypoints and players"
            : selectedSearchItemName() + " — " + results.size() + " waypoints";
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
                label = waypoint.getName() + "  " + waypoint.getX() + ", " + waypoint.getZ();
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
        if (id == null) return "Selected item";
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? this.selectedSearchItem : new ItemStack(item).getHoverName().getString();
    }

    private void drawWaypointItemPicker(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                        int mouseX, int mouseY) {
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
        graphics.fill(panelX, panelY, panelX + 290, panelY + 238, 0xFF202020);
        graphics.outline(panelX, panelY, 290, 238, 0xFFAAAAAA);
        graphics.centeredText(this.font, "Choose item to find waypoints", panelX + 145, panelY + 8, 0xFFFFFFFF);
        graphics.fill(panelX + 9, panelY + 25, panelX + 254, panelY + 45, 0xFF101010);
        graphics.outline(panelX + 9, panelY + 25, 245, 20, 0xFF777777);
        String visibleSearch = this.itemPickerSearch.length() > 34
            ? this.itemPickerSearch.substring(this.itemPickerSearch.length() - 34) : this.itemPickerSearch;
        graphics.text(this.font, visibleSearch.isEmpty() ? "Search items by name" : visibleSearch + "|",
            panelX + 14, panelY + 31, visibleSearch.isEmpty() ? 0xFF888888 : 0xFFFFFFFF);
        graphics.fill(panelX + 260, panelY + 25, panelX + 282, panelY + 45, 0xFF41414A);
        graphics.centeredText(this.font, "X", panelX + 271, panelY + 31, 0xFFFFFFFF);

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
        graphics.text(this.font, this.filteredSearchItems.size() + " items — scroll to browse",
            panelX + 12, panelY + 224, 0xFFCCCCCC);
    }

    private boolean handleWaypointItemPickerClick(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
        if (event.x() >= panelX + 258 && event.x() <= panelX + 282
            && event.y() >= panelY + 7 && event.y() <= panelY + 27) {
            this.itemPickerOpen = false;
            return true;
        }
        int gridX = panelX + 18;
        int gridY = panelY + 54;
        if (event.x() >= gridX && event.x() < gridX + ITEM_PICKER_COLUMNS * 28
            && event.y() >= gridY && event.y() < gridY + ITEM_PICKER_ROWS * 28) {
            int column = (int) (event.x() - gridX) / 28;
            int row = (int) (event.y() - gridY) / 28;
            selectWaypointSearchItem((this.itemPickerScrollRow + row) * ITEM_PICKER_COLUMNS + column);
        }
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (!this.itemPickerOpen && event.key() == InputConstants.KEY_TAB
            && this.waypointSearchField != null && this.waypointSearchField.isFocused()) {
            String currentDim = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.dimension().identifier().toString()
                : "minecraft:overworld";
            String suggestion = getPlayerNameSuggestion(currentDim);
            if (suggestion != null) {
                this.waypointSearchField.setValue(suggestion);
                return true;
            }
        }
        if (!this.itemPickerOpen) return super.keyPressed(event);
        if (event.key() == InputConstants.KEY_ESCAPE) {
            this.itemPickerOpen = false;
            return true;
        }
        if (event.key() == InputConstants.KEY_BACKSPACE && !this.itemPickerSearch.isEmpty()) {
            int end = this.itemPickerSearch.offsetByCodePoints(this.itemPickerSearch.length(), -1);
            this.itemPickerSearch = this.itemPickerSearch.substring(0, end);
            filterWaypointItems();
            return true;
        }
        if (event.key() == InputConstants.KEY_RETURN) {
            selectWaypointSearchItem(0);
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!this.itemPickerOpen) return super.charTyped(event);
        if (event.isAllowedChatCharacter()
            && this.itemPickerSearch.codePointCount(0, this.itemPickerSearch.length()) < 48) {
            this.itemPickerSearch += event.codepointAsString();
            filterWaypointItems();
        }
        return true;
    }

    private void drawLocalPlayerMarker(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                       int centerX, int centerY) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        double playerX = centerX + (player.getX() + panX) * scale;
        double playerY = centerY + (player.getZ() + panY) * scale;
        double[] marker = markerScreenPosition(playerX, playerY, centerX, centerY);
        boolean onScreen = playerX >= 10 && playerX <= width - 10
            && playerY >= 10 && playerY <= height - 24;
        float rotation = onScreen
            ? player.getYRot() + 180.0f
            : (float) Math.toDegrees(Math.atan2(playerX - centerX, -(playerY - centerY)));
        drawPlayerArrow(graphics, marker[0], marker[1], rotation, 0xFFFFFFFF);
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
        long mixed = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 23);
        float hue = (float) ((mixed >>> 40 & 0xFFFFFFL) / 16777216.0);
        return java.awt.Color.HSBtoRGB(hue, 0.82f, 1.0f) | 0xFF000000;
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
        if (this.itemPickerOpen) return handleWaypointItemPickerClick(event);
        if (handlePlayerNameSuggestionClick(event)) return true;
        if (handleWaypointSearchResultClick(event)) return true;

        // Let GUI controls (such as the Settings button) handle their clicks first.
        if (super.mouseClicked(event, isDouble)) return true;

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            String currentDim = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.dimension().identifier().toString()
                : "minecraft:overworld";

            if (WorldMapConfig.showPlayers() && Minecraft.getInstance().player != null) {
                var localPlayer = Minecraft.getInstance().player;
                double localX = centerX + (localPlayer.getX() + panX) * scale;
                double localY = centerY + (localPlayer.getZ() + panY) * scale;
                double[] localMarker = markerScreenPosition(localX, localY, centerX, centerY);
                if (isNearMarker(event.x(), event.y(), localMarker[0], localMarker[1])) {
                    centerMapOn(localPlayer.getX(), localPlayer.getZ());
                    return true;
                }
            }

            if (WorldMapConfig.showPlayers()) {
                for (com.runterya.worldmap.network.PlayerPosPayload.PlayerPos player : ClientMapManager.getOtherPlayers()) {
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
                String dim = Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.dimension().identifier().toString()
                    : "minecraft:overworld";
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
            String dim = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.dimension().identifier().toString()
                : "minecraft:overworld";

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
                int blockY = 64; // Default Y
                if (Minecraft.getInstance().player != null) {
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

        String currentDim = Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.dimension().identifier().toString()
            : "minecraft:overworld";
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

        String currentDim = Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.dimension().identifier().toString()
            : "minecraft:overworld";
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
