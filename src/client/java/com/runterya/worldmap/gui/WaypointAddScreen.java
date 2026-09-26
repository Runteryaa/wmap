package com.runterya.worldmap.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import com.runterya.worldmap.client.waypoint.Waypoint;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import com.runterya.worldmap.network.WaypointIcon;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WaypointAddScreen extends Screen {
    private EditBox nameField;
    private EditBox categoryField;
    private EditBox noteField;
    private final int x, y, z;
    private final String dimension;
    private final Screen parent;
    private final Waypoint editingWaypoint;

    public WaypointAddScreen(Screen parent, int x, int y, int z, String dimension) {
        super(Localization.component("waypoint.add_title"));
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.editingWaypoint = null;
    }

    public WaypointAddScreen(Screen parent, Waypoint waypoint) {
        super(Localization.component("waypoint.edit_title"));
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
    private EditBox itemSearchField;
    private Button itemPickerCloseButton;
    private final List<AbstractWidget> itemPickerHiddenWidgets = new ArrayList<>();
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
        this.itemPickerHiddenWidgets.clear();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameField = new EditBox(this.font, centerX - 100, centerY - 92, 200, 20, Localization.component("waypoint.name"));
        this.nameField.setValue(this.editingWaypoint == null ? Localization.text("waypoint.new_name") : this.editingWaypoint.getName());
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        this.xField = new EditBox(this.font, centerX - 100, centerY - 62, 60, 20, Component.literal("X"));
        this.xField.setValue(String.valueOf(this.x));
        this.addRenderableWidget(this.xField);

        this.yField = new EditBox(this.font, centerX - 30, centerY - 62, 60, 20, Component.literal("Y"));
        this.yField.setValue(String.valueOf(this.y));
        this.addRenderableWidget(this.yField);

        this.zField = new EditBox(this.font, centerX + 40, centerY - 62, 60, 20, Component.literal("Z"));
        this.zField.setValue(String.valueOf(this.z));
        this.addRenderableWidget(this.zField);

        this.currentColor = this.editingWaypoint == null ? new java.util.Random().nextInt(0xFFFFFF) : this.editingWaypoint.getColor() & 0xFFFFFF;
        this.currentIcon = this.editingWaypoint == null ? "" : this.editingWaypoint.getIcon();
        this.colorField = new EditBox(this.font, centerX - 100, centerY - 32, 60, 20, Localization.component("waypoint.color_hex"));
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
            }).bounds(centerX + 2, centerY - 32, 98, 20).build());
        }

        this.categoryField = new EditBox(this.font, centerX - 100, centerY - 4, 200, 20, Localization.component("waypoint.category"));
        this.categoryField.setHint(Localization.component("waypoint.category_optional"));
        this.categoryField.setMaxLength(48);
        this.categoryField.setValue(this.editingWaypoint == null ? "" : this.editingWaypoint.getCategory());
        this.addRenderableWidget(this.categoryField);

        this.noteField = new EditBox(this.font, centerX - 100, centerY + 22, 200, 20, Localization.component("waypoint.note"));
        this.noteField.setHint(Localization.component("waypoint.note_optional"));
        this.noteField.setMaxLength(256);
        this.noteField.setValue(this.editingWaypoint == null ? "" : this.editingWaypoint.getNote());
        this.addRenderableWidget(this.noteField);

        this.itemButton = this.addRenderableWidget(Button.builder(iconLabel(), button -> openItemPicker())
            .bounds(centerX - 76, centerY + 48, 176, 20).build());

        this.addRenderableWidget(Button.builder(Localization.component(this.editingWaypoint == null ? "waypoint.add" : "waypoint.save"), button -> {
            int color = 0xFF000000 | this.currentColor;
            int finalX = this.x;
            int finalY = this.y;
            int finalZ = this.z;
            try { finalX = Integer.parseInt(this.xField.getValue()); } catch (Exception ignored) {}
            try { finalY = Integer.parseInt(this.yField.getValue()); } catch (Exception ignored) {}
            try { finalZ = Integer.parseInt(this.zField.getValue()); } catch (Exception ignored) {}

            Waypoint wp = new Waypoint(this.nameField.getValue(), finalX, finalY, finalZ, color, this.dimension,
                this.isGlobal, "", this.currentIcon, this.categoryField.getValue(), this.noteField.getValue());
            if (this.editingWaypoint == null) WaypointManager.addWaypoint(wp);
            else WaypointManager.updateWaypoint(this.editingWaypoint, wp);
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Localization.component(this.editingWaypoint == null ? "waypoint.added" : "waypoint.updated"));
            }
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(centerX - 100, centerY + 76, 98, 20).build());

        this.addRenderableWidget(Button.builder(Localization.component("waypoint.cancel"), button -> {
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent);
        }).bounds(centerX + 2, centerY + 76, 98, 20).build());

        for (var child : this.children()) {
            if (child instanceof AbstractWidget widget) this.itemPickerHiddenWidgets.add(widget);
        }
        int pickerX = Math.max(8, (this.width - 290) / 2);
        int pickerY = Math.max(8, (this.height - 238) / 2);
        this.itemSearchField = new EditBox(this.font, pickerX + 9, pickerY + 25, 245, 20,
            Localization.component("waypoint.search_items"));
        this.itemSearchField.setHint(Localization.component("waypoint.search_items"));
        this.itemSearchField.setMaxLength(48);
        this.itemSearchField.setResponder(text -> {
            this.itemSearch = text;
            filterItems();
        });
        this.itemSearchField.setValue(this.itemSearch);
        this.itemSearchField.visible = false;
        this.addRenderableWidget(this.itemSearchField);
        this.itemPickerCloseButton = this.addRenderableWidget(Button.builder(Component.literal("X"), button ->
            setItemPickerOpen(false)
        ).bounds(pickerX + 260, pickerY + 25, 22, 20).build());
        this.itemPickerCloseButton.visible = false;
        setItemPickerOpen(this.itemPickerOpen);
    }

    private Component visibilityLabel() {
        return Localization.component(this.isGlobal ? "waypoint.public_on" : "waypoint.public_off");
    }

    private Component iconLabel() {
        if (this.currentIcon.isBlank()) return Localization.component("waypoint.icon_none");
        Identifier id = Identifier.tryParse(this.currentIcon);
        if (id == null) return Localization.component("waypoint.icon_none");
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return Localization.component("waypoint.icon_item", item == Items.AIR
            ? this.currentIcon : new ItemStack(item).getHoverName().getString());
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
        if (this.itemSearchField != null) this.itemSearchField.setValue("");
        setItemPickerOpen(true);
    }

    private void setItemPickerOpen(boolean open) {
        this.itemPickerOpen = open;
        for (AbstractWidget widget : this.itemPickerHiddenWidgets) widget.visible = !open;
        if (this.itemSearchField != null) {
            this.itemSearchField.visible = open;
            this.itemSearchField.active = open;
            if (open) {
                this.itemSearchField.setFocused(true);
                this.setFocused(this.itemSearchField);
            } else {
                this.itemSearchField.setFocused(false);
                if (this.nameField != null) {
                    this.nameField.setFocused(false);
                    this.setFocused(this.nameField);
                }
            }
        }
        if (this.itemPickerCloseButton != null) this.itemPickerCloseButton.visible = open;
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
            setItemPickerOpen(false);
            this.itemButton.setMessage(iconLabel());
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (!this.itemPickerOpen) return super.keyPressed(event);
        if (event.key() == InputConstants.KEY_ESCAPE) {
            setItemPickerOpen(false);
            return true;
        }
        if (event.key() == InputConstants.KEY_RETURN) {
            selectItem(0);
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
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                int panelX = Math.max(8, (this.width - 290) / 2);
                int panelY = Math.max(8, (this.height - 238) / 2);
                int gridX = panelX + 18;
                int gridY = panelY + 54;
                int gridWidth = PICKER_COLUMNS * 28;
                int gridHeight = PICKER_ROWS * 28;
                if (mouseX >= gridX && mouseX < gridX + gridWidth
                    && mouseY >= gridY && mouseY < gridY + gridHeight) {
                    int column = (int) (mouseX - gridX) / 28;
                    int row = (int) (mouseY - gridY) / 28;
                    selectItem((this.itemScrollRow + row) * PICKER_COLUMNS + column);
                    return true;
                }
            }
            super.mouseClicked(event, isDouble);
            return true;
        }
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int boxX = centerX - 30;
        int boxY = centerY - 32;

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
        if (this.itemPickerOpen) drawItemPickerBackground(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

        if (this.itemPickerOpen) {
            drawItemPickerContents(graphics, mouseX, mouseY);
            return;
        }
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        if (this.editingWaypoint != null) {
            String owner = this.editingWaypoint.getCreatorName();
            if (owner.isBlank() && !this.editingWaypoint.isGlobal()
                && this.minecraft != null && this.minecraft.player != null) {
                owner = this.minecraft.player.getName().getString();
            }
            if (!owner.isBlank()) {
                graphics.text(this.font, Localization.text("waypoint.owner", owner), centerX - 100, centerY - 106, 0xFFCCCCCC);
            }
        }

        int iconSlotX = centerX - 100;
        int iconSlotY = centerY + 48;
        graphics.fill(iconSlotX, iconSlotY, iconSlotX + 20, iconSlotY + 20, 0xFF41414A);
        graphics.outline(iconSlotX, iconSlotY, 20, 20, 0xFF777777);
        ItemStack selectedIcon = selectedIconStack();
        if (selectedIcon.isEmpty()) {
            graphics.centeredText(this.font, "□", iconSlotX + 10, iconSlotY + 6, 0xFFFFFFFF);
        } else {
            graphics.item(selectedIcon, iconSlotX + 2, iconSlotY + 2);
        }
        
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
        int boxY = centerY - 32;
        graphics.fill(boxX - 1, boxY - 1, boxX + 21, boxY + 21, 0xFFA0A0A0); // Light gray border
        graphics.fill(boxX, boxY, boxX + 20, boxY + 20, 0xFF000000); // Black border
        graphics.fill(boxX + 1, boxY + 1, boxX + 19, boxY + 19, 0xFF000000 | this.currentColor); // Color

    }

    private ItemStack selectedIconStack() {
        if (this.currentIcon.isBlank()) return ItemStack.EMPTY;
        Identifier id = Identifier.tryParse(this.currentIcon);
        if (id == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private void drawItemPickerBackground(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
        graphics.fill(panelX, panelY, panelX + 290, panelY + 238, 0xFF202020);
        graphics.outline(panelX, panelY, 290, 238, 0xFFAAAAAA);
        graphics.centeredText(this.font, Localization.text("waypoint.choose_item"), panelX + 145, panelY + 8, 0xFFFFFFFF);
    }

    private void drawItemPickerContents(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int panelX = Math.max(8, (this.width - 290) / 2);
        int panelY = Math.max(8, (this.height - 238) / 2);
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
                if (hovered) graphics.setTooltipForNextFrame(this.font, Localization.component("waypoint.no_icon"), mouseX, mouseY);
            } else {
                graphics.item(entry.stack(), x + 4, y + 4);
                if (hovered) graphics.setTooltipForNextFrame(this.font, entry.stack(), mouseX, mouseY);
            }
        }
        graphics.text(this.font, Localization.text("waypoint.item_count", this.filteredItems.size()), panelX + 12,
            panelY + 224, 0xFFCCCCCC);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
