package com.runterya.worldmap.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import com.runterya.worldmap.network.WaypointIcon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WaypointAddScreen extends Screen {
    private EditBox nameField;
    private final int x, y, z;
    private final String dimension;
    private final Screen parent;
    private final Waypoint editingWaypoint;

    public WaypointAddScreen(Screen parent, int x, int y, int z, String dimension) {
        super(Component.literal("Add Waypoint"));
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.editingWaypoint = null;
    }

    public WaypointAddScreen(Screen parent, Waypoint waypoint) {
        super(Component.literal("Edit Waypoint"));
        this.parent = parent;
        this.x = waypoint.getX();
        this.y = waypoint.getY();
        this.z = waypoint.getZ();
        this.dimension = waypoint.getDimension();
        this.editingWaypoint = waypoint;
        this.isGlobal = waypoint.isGlobal();
    }

    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private EditBox colorField;
    private int currentColor;
    private boolean showColorWheel = false;
    private boolean isGlobal = false;
    private String currentIcon = "";
    private boolean itemPickerOpen;
    private String itemSearch = "";
    private int itemScrollRow;
    private Button itemButton;
    private List<ItemEntry> allItems = List.of();
    private List<ItemEntry> filteredItems = List.of();
    private static final int PICKER_COLUMNS = 9;
    private static final int PICKER_ROWS = 5;
    private static final Identifier NO_ICON_ID = Identifier.fromNamespaceAndPath("worldmap", "no_icon");
    private record ItemEntry(Identifier id, ItemStack stack, String searchName) {}
    private static final net.minecraft.resources.Identifier COLOR_WHEEL = net.minecraft.resources.Identifier.fromNamespaceAndPath("worldmap", "textures/gui/color_wheel.png");

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameField = new EditBox(this.font, centerX - 100, centerY - 80, 200, 20, Component.literal("Waypoint Name"));
        this.nameField.setValue(this.editingWaypoint == null ? "New Waypoint" : this.editingWaypoint.getName());
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        this.xField = new EditBox(this.font, centerX - 100, centerY - 50, 60, 20, Component.literal("X"));
        this.xField.setValue(String.valueOf(this.x));
        this.addRenderableWidget(this.xField);

        this.yField = new EditBox(this.font, centerX - 30, centerY - 50, 60, 20, Component.literal("Y"));
        this.yField.setValue(String.valueOf(this.y));
        this.addRenderableWidget(this.yField);

        this.zField = new EditBox(this.font, centerX + 40, centerY - 50, 60, 20, Component.literal("Z"));
        this.zField.setValue(String.valueOf(this.z));
        this.addRenderableWidget(this.zField);

        this.currentColor = this.editingWaypoint == null ? new java.util.Random().nextInt(0xFFFFFF) : this.editingWaypoint.getColor() & 0xFFFFFF;
        this.currentIcon = this.editingWaypoint == null ? "" : this.editingWaypoint.getIcon();
        this.colorField = new EditBox(this.font, centerX - 100, centerY - 20, 60, 20, Component.literal("Color Hex"));
        this.colorField.setValue(String.format("%06X", this.currentColor));
        this.colorField.setMaxLength(6);
        this.colorField.setResponder(text -> {
            try {
                this.currentColor = Integer.parseInt(text, 16);
            } catch (Exception ignored) {}
        });
        this.addRenderableWidget(this.colorField);

        if (WaypointManager.isServerWaypointSharingAvailable()) {
            this.addRenderableWidget(Button.builder(visibilityLabel(), button -> {
                this.isGlobal = !this.isGlobal;
                button.setMessage(visibilityLabel());
            }).bounds(centerX + 2, centerY - 20, 98, 20).build());
        }

        this.itemButton = this.addRenderableWidget(Button.builder(iconLabel(), button -> openItemPicker())
            .bounds(centerX - 100, centerY + 4, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal(this.editingWaypoint == null ? "Add" : "Save"), button -> {
            int color = 0xFF000000 | this.currentColor;
            int finalX = this.x;
            int finalY = this.y;
            int finalZ = this.z;
            try { finalX = Integer.parseInt(this.xField.getValue()); } catch (Exception ignored) {}
            try { finalY = Integer.parseInt(this.yField.getValue()); } catch (Exception ignored) {}
            try { finalZ = Integer.parseInt(this.zField.getValue()); } catch (Exception ignored) {}

            Waypoint wp = new Waypoint(this.nameField.getValue(), finalX, finalY, finalZ, color, this.dimension,
                this.isGlobal, "", this.currentIcon);
            if (this.editingWaypoint == null) WaypointManager.addWaypoint(wp);
            else WaypointManager.updateWaypoint(this.editingWaypoint, wp);
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal(this.editingWaypoint == null ? "Waypoint added!" : "Waypoint updated!"));
            }
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(centerX - 100, centerY + 32, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(centerX + 2, centerY + 32, 98, 20).build());
    }

    private Component visibilityLabel() {
        return Component.literal(this.isGlobal ? "[✓] Public" : "[ ] Public");
    }

    private Component iconLabel() {
        if (this.currentIcon.isBlank()) return Component.literal("Waypoint icon: None");
        Identifier id = Identifier.tryParse(this.currentIcon);
        if (id == null) return Component.literal("Waypoint icon: None");
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return Component.literal("Waypoint item: " + (item == Items.AIR ? this.currentIcon : new ItemStack(item).getHoverName().getString()));
    }

    private void openItemPicker() {
        List<ItemEntry> items = new ArrayList<>();
        BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).forEach(item -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                ItemStack stack = new ItemStack(item);
                items.add(new ItemEntry(id, stack,
                    (stack.getHoverName().getString() + " " + id).toLowerCase(Locale.ROOT)));
            }
        });
        items.add(0, new ItemEntry(NO_ICON_ID, ItemStack.EMPTY, "none square marker"));
        this.allItems = List.copyOf(items);
        this.filteredItems = this.allItems;
        this.itemSearch = "";
        this.itemScrollRow = 0;
        this.itemPickerOpen = true;
    }

    private void filterItems() {
        String query = this.itemSearch.trim().toLowerCase(Locale.ROOT);
        this.filteredItems = this.allItems.stream()
            .filter(item -> query.isEmpty() || item.searchName().contains(query)).toList();
        this.itemScrollRow = 0;
    }

    private void selectItem(int filteredIndex) {
        if (filteredIndex >= 0 && filteredIndex < this.filteredItems.size()) {
            Identifier selectedId = this.filteredItems.get(filteredIndex).id();
            this.currentIcon = selectedId.equals(NO_ICON_ID) ? "" : selectedId.toString();
            this.itemPickerOpen = false;
            this.itemButton.setMessage(iconLabel());
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (!this.itemPickerOpen) return super.keyPressed(event);
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.itemPickerOpen = false;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !this.itemSearch.isEmpty()) {
            int end = this.itemSearch.offsetByCodePoints(this.itemSearch.length(), -1);
            this.itemSearch = this.itemSearch.substring(0, end);
            filterItems();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER) {
            selectItem(0);
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!this.itemPickerOpen) return super.charTyped(event);
        if (event.isAllowedChatCharacter() && this.itemSearch.codePointCount(0, this.itemSearch.length()) < 48) {
            this.itemSearch += event.codepointAsString();
            filterItems();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.itemPickerOpen) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int rows = (this.filteredItems.size() + PICKER_COLUMNS - 1) / PICKER_COLUMNS;
        int maxScroll = Math.max(0, rows - PICKER_ROWS);
        this.itemScrollRow = Math.max(0, Math.min(maxScroll, this.itemScrollRow - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isDouble) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        if (this.itemPickerOpen) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_1) return true;
            int panelX = Math.max(8, (this.width - 290) / 2);
            int panelY = Math.max(8, (this.height - 238) / 2);
            if (mouseX >= panelX + 258 && mouseX <= panelX + 282
                && mouseY >= panelY + 7 && mouseY <= panelY + 27) {
                this.itemPickerOpen = false;
                return true;
            }
            int gridX = panelX + 18;
            int gridY = panelY + 54;
            int gridWidth = PICKER_COLUMNS * 28;
            int gridHeight = PICKER_ROWS * 28;
            if (mouseX >= gridX && mouseX < gridX + gridWidth && mouseY >= gridY && mouseY < gridY + gridHeight) {
                int column = (int) (mouseX - gridX) / 28;
                int row = (int) (mouseY - gridY) / 28;
                selectItem((this.itemScrollRow + row) * PICKER_COLUMNS + column);
                return true;
            }
            return true;
        }
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int boxX = centerX - 30;
        int boxY = centerY - 20;

        if (button == 0) {
            // Check if clicked on color preview box
            if (mouseX >= boxX && mouseX <= boxX + 20 && mouseY >= boxY && mouseY <= boxY + 20) {
                this.showColorWheel = !this.showColorWheel;
                return true;
            }

            // Check if clicked inside color wheel
            if (this.showColorWheel) {
                int wheelSize = 80;
                int wheelHalf = wheelSize / 2;
                int wheelX = centerX + 10;
                int wheelY = centerY - wheelHalf;
                if (mouseX >= wheelX && mouseX <= wheelX + wheelSize && mouseY >= wheelY && mouseY <= wheelY + wheelSize) {
                    double dx = mouseX - (wheelX + wheelHalf);
                    double dy = mouseY - (wheelY + wheelHalf);
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist <= wheelHalf) {
                        double angle = Math.atan2(dy, dx);
                        float hue = (float) (angle / (2 * Math.PI));
                        if (hue < 0) hue += 1.0f;
                        float saturation = (float) (dist / (double)wheelHalf);
                        
                        this.currentColor = java.awt.Color.HSBtoRGB(hue, saturation, 1.0f) & 0xFFFFFF;
                        this.colorField.setValue(String.format("%06X", this.currentColor));
                        return true;
                    }
                }
            }
        }
        
        // Hide color wheel if clicked elsewhere
        if (this.showColorWheel) {
            this.showColorWheel = false;
        }

        return super.mouseClicked(event, isDouble);
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Draw Color Wheel if enabled
        if (this.showColorWheel) {
                int wheelSize = 80;
                int wheelHalf = wheelSize / 2;
                int wheelX = centerX + 10;
                int wheelY = centerY - wheelHalf;
                graphics.blit(COLOR_WHEEL, wheelX, wheelY, wheelX + wheelSize, wheelY + wheelSize, 0.0f, 1.0f, 0.0f, 1.0f);
        }

        // Draw color preview box
        int boxX = centerX - 30;
        int boxY = centerY - 20;
        graphics.fill(boxX - 1, boxY - 1, boxX + 21, boxY + 21, 0xFFA0A0A0); // Light gray border
        graphics.fill(boxX, boxY, boxX + 20, boxY + 20, 0xFF000000); // Black border
        graphics.fill(boxX + 1, boxY + 1, boxX + 19, boxY + 19, 0xFF000000 | this.currentColor); // Color

        if (this.itemPickerOpen) drawItemPicker(graphics, mouseX, mouseY);
    }

    private void drawItemPicker(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
        graphics.fill(panelX, panelY, panelX + 290, panelY + 238, 0xFF202020);
        graphics.outline(panelX, panelY, panelX + 290, panelY + 238, 0xFFAAAAAA);
        graphics.centeredText(this.font, "Choose waypoint item", panelX + 145, panelY + 8, 0xFFFFFFFF);
        graphics.fill(panelX + 9, panelY + 25, panelX + 254, panelY + 45, 0xFF101010);
        graphics.outline(panelX + 9, panelY + 25, panelX + 254, panelY + 45, 0xFF777777);
        String visibleSearch = this.itemSearch.length() > 34
            ? this.itemSearch.substring(this.itemSearch.length() - 34) : this.itemSearch;
        graphics.text(this.font, visibleSearch + "_", panelX + 14, panelY + 31, 0xFFFFFFFF);
        graphics.fill(panelX + 260, panelY + 25, panelX + 282, panelY + 45, 0xFF41414A);
        graphics.centeredText(this.font, "X", panelX + 271, panelY + 31, 0xFFFFFFFF);

        int firstIndex = this.itemScrollRow * PICKER_COLUMNS;
        int visibleCount = PICKER_COLUMNS * PICKER_ROWS;
        int count = Math.min(visibleCount, this.filteredItems.size() - firstIndex);
        for (int i = 0; i < count; i++) {
            int index = firstIndex + i;
            ItemEntry entry = this.filteredItems.get(index);
            int column = i % PICKER_COLUMNS;
            int row = i / PICKER_COLUMNS;
            int x = panelX + 18 + column * 28;
            int y = panelY + 54 + row * 28;
            boolean hovered = mouseX >= x && mouseX < x + 24 && mouseY >= y && mouseY < y + 24;
            boolean noIcon = entry.id().equals(NO_ICON_ID);
            boolean selected = noIcon ? this.currentIcon.isBlank() : entry.id().toString().equals(this.currentIcon);
            graphics.fill(x, y, x + 24, y + 24, selected ? 0xFF75662E : hovered ? 0xFF555555 : 0xFF333333);
            if (noIcon) {
                graphics.centeredText(this.font, "□", x + 12, y + 7, 0xFFFFFFFF);
                if (hovered) graphics.setTooltipForNextFrame(this.font, Component.literal("No icon (square marker)"), mouseX, mouseY);
            } else {
                graphics.item(entry.stack(), x + 4, y + 4);
                if (hovered) graphics.setTooltipForNextFrame(this.font, entry.stack(), mouseX, mouseY);
            }
        }
        graphics.text(this.font, this.filteredItems.size() + " items — scroll to browse", panelX + 12,
            panelY + 224, 0xFFCCCCCC);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
