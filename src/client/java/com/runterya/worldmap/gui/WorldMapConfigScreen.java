package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import com.runterya.worldmap.client.ClientMapManager;
import com.runterya.worldmap.client.ClientMapStorage;
import com.runterya.worldmap.client.MapImportExport;
import com.runterya.worldmap.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class WorldMapConfigScreen extends Screen {
    private final Screen parent;
    private String feedback = "";

    public WorldMapConfigScreen(Screen parent) {
        super(Component.literal("WorldMap Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addRenderableWidget(Button.builder(toggleLabel(), button -> {
            WorldMapConfig.setOpenWaypointActionsOnLook(!WorldMapConfig.openWaypointActionsOnLook());
            button.setMessage(toggleLabel());
        }).bounds(centerX - 150, centerY - 40, 300, 20).build());

        this.addRenderableWidget(Button.builder(exploredAreasLabel(), button -> {
            WorldMapConfig.toggleShowExploredAreas();
            button.setMessage(exploredAreasLabel());
        }).bounds(centerX - 150, centerY - 14, 300, 20).build());

        this.addRenderableWidget(Button.builder(explorationFilterLabel(), button -> {
            WorldMapConfig.cycleMapLayer();
            button.setMessage(explorationFilterLabel());
        }).bounds(centerX - 150, centerY + 12, 300, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Import world"), button -> importWorld())
            .bounds(centerX - 150, centerY + 38, 146, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Export world"), button -> exportWorld())
            .bounds(centerX + 4, centerY + 38, 146, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button ->
            com.runterya.worldmap.client.ClientPlatform.setScreen(this.minecraft, this.parent)
        ).bounds(centerX - 100, centerY + 68, 200, 20).build());
    }

    private static Component toggleLabel() {
        return Component.literal("B while looking at a waypoint: "
            + (WorldMapConfig.openWaypointActionsOnLook() ? "Waypoint actions" : "Add waypoint"));
    }

    private static Component exploredAreasLabel() {
        return Component.literal("Explored map: " + (WorldMapConfig.showExploredAreas() ? "Shown" : "Hidden"));
    }

    private static Component explorationFilterLabel() {
        String label = switch (WorldMapConfig.mapLayer()) {
            case MY_EXPLORED -> "My explored areas";
            case OTHERS_EXPLORED -> "Others' explored areas";
            case ALL -> "All explored areas";
        };
        return Component.literal("Explored by: " + label);
    }

    private void exportWorld() {
        Path destination = chooseArchive(true);
        if (destination == null) return;
        List<com.runterya.worldmap.client.waypoint.Waypoint> waypoints = List.copyOf(WaypointManager.getWaypoints());
        setBusy(true, "Exporting map and waypoints...");
        CompletableFuture.runAsync(() -> {
            try {
                synchronized (ClientMapStorage.class) {
                    MapImportExport.exportCurrentWorld(destination, waypoints);
                }
                Minecraft.getInstance().execute(() -> setBusy(false, "Exported to " + destination.getFileName()));
            } catch (Exception exception) {
                Minecraft.getInstance().execute(() -> setBusy(false, "Export failed: " + exception.getMessage()));
            }
        });
    }

    private void importWorld() {
        Path source = chooseArchive(false);
        if (source == null) return;
        setBusy(true, "Importing map and waypoints...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return MapImportExport.importIntoCurrentWorld(source);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                setBusy(false, "Import failed: " + cause.getMessage());
                return;
            }
            int importedWaypoints = WaypointManager.importWaypoints(result.waypoints());
            ClientMapStorage.loadAllIntoManager();
            ClientMapManager.refreshLayer();
            setBusy(false, "Imported " + result.regionFiles() + " map regions and " + importedWaypoints + " waypoints.");
        }));
    }

    private Path chooseArchive(boolean save) {
        try {
            AtomicReference<Path> selectedPath = new AtomicReference<>();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            Runnable showDialog = () -> {
                try {
                    File gameDirectory = Minecraft.getInstance().gameDirectory;
                    JFileChooser chooser = new JFileChooser(gameDirectory);
                    chooser.setDialogTitle(save ? "Export WorldMap data" : "Import WorldMap data");
                    chooser.setFileFilter(new FileNameExtensionFilter("WorldMap archive (*.wmap)", "wmap"));
                    if (save) chooser.setSelectedFile(new File(gameDirectory, "worldmap-export.wmap"));
                    int result = save ? chooser.showSaveDialog(null) : chooser.showOpenDialog(null);
                    if (result != JFileChooser.APPROVE_OPTION) return;

                    Path selected = chooser.getSelectedFile().toPath();
                    if (save && !selected.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wmap")) {
                        selected = selected.resolveSibling(selected.getFileName() + ".wmap");
                    }
                    selectedPath.set(selected);
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
            };
            if (SwingUtilities.isEventDispatchThread()) showDialog.run();
            else SwingUtilities.invokeAndWait(showDialog);
            if (failure.get() != null) throw failure.get();
            return selectedPath.get();
        } catch (RuntimeException exception) {
            feedback = "Could not open file chooser: " + exception.getMessage();
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            feedback = "File chooser was interrupted";
            return null;
        } catch (java.lang.reflect.InvocationTargetException exception) {
            feedback = "Could not open file chooser: " + exception.getCause().getMessage();
            return null;
        }
    }

    private void setBusy(boolean busy, String message) {
        this.feedback = message;
        this.children().forEach(child -> {
            if (child instanceof Button button && !button.getMessage().getString().equals("Done")) {
                button.active = !busy;
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (!feedback.isEmpty()) {
            graphics.centeredText(Minecraft.getInstance().font, feedback, this.width / 2, this.height - 16,
                0xFFFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
