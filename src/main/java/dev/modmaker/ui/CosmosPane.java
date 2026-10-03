package dev.modmaker.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.PlanetGrids;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planets &amp; sectors workspace: an arrangement tree of the mod's planets with their sectors
 * grouped underneath (a sector declares its planet via the {@code planet} field; the game defaults
 * to serpulo). Vanilla planets that mod sectors reference appear as read-only group roots. Selecting
 * a planet or sector shows a live grid preview of that planet.
 */
public final class CosmosPane extends BorderPane {

    private final ProjectController controller;
    private final TreeView<String> tree = new TreeView<>();
    private final PlanetPreview preview = new PlanetPreview();
    private ModProject project;

    public CosmosPane(ProjectController controller) {
        this.controller = controller;
        getStyleClass().add("panel");

        tree.setCellFactory(view -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value);
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((observable, was, item) -> {
            if (item != null && item.getValue() != null) {
                ContentRecord record = recordFor(item);
                if (record != null) {
                    controller.select(record);
                }
            }
        });

        Button addPlanet = new Button(Messages.t("cosmos.add.planet"));
        addPlanet.getStyleClass().add("tool-button");
        addPlanet.setOnAction(event -> controller.addContent(ContentType.planet));
        Button addSector = new Button(Messages.t("cosmos.add.sector"));
        addSector.getStyleClass().add("tool-button");
        addSector.setOnAction(event -> addSectorUnderSelection());

        HBox buttons = new HBox(8, addPlanet, addSector);
        setTop(buttons);
        setCenter(tree);
        setRight(preview);

        controller.onProjectChanged(this::refresh);
        controller.onSelectionChanged(record -> updatePreview());
        refresh();
    }

    /** Adds a sector, defaulting its planet field to the currently selected planet. */
    private void addSectorUnderSelection() {
        ContentRecord selected = controller.selected();
        ContentRecord sector = controller.addContent(ContentType.sector);
        if (selected != null && selected.type == ContentType.planet) {
            controller.setField(sector, "planet", new com.google.gson.JsonPrimitive(selected.name));
        }
    }

    public void refresh() {
        project = controller.project();
        tree.setRoot(buildTree());
        tree.setShowRoot(false);
        updatePreview();
    }

    /** Feeds the preview workbench from the selected record (planet or sector). */
    private void updatePreview() {
        preview.setSource(controller.selected(), project);
    }

    private TreeItem<String> buildTree() {
        TreeItem<String> root = new TreeItem<>();
        if (project == null) {
            return root;
        }
        String internal = project.internalName();

        Map<String, ContentRecord> planetsByName = new LinkedHashMap<>();
        java.util.LinkedHashSet<ContentRecord> planets = new java.util.LinkedHashSet<>();
        for (ContentRecord record : project.contents) {
            if (record.type == ContentType.planet && !record.isInline()) {
                planetsByName.put(record.name, record);
                planetsByName.put(record.fullName(internal), record);
                planets.add(record);
            }
        }

        Map<String, TreeItem<String>> groupItems = new LinkedHashMap<>();
        for (ContentRecord planet : planets) {
            TreeItem<String> item = new TreeItem<>(nodeLabel(planet));
            item.setExpanded(true);
            groupItems.put(planet.name, item);
            root.getChildren().add(item);
        }

        for (ContentRecord sector : project.contents) {
            if (sector.type != ContentType.sector || sector.isInline()) {
                continue;
            }
            String target = sectorPlanet(sector);
            String bare = target.startsWith(internal + "-")
                ? target.substring(internal.length() + 1)
                : target;
            TreeItem<String> group = groupItems.get(bare);
            if (group == null) {
                boolean vanilla = registryVanilla(bare);
                String label = Messages.t("cosmos.vanilla") + " · " + bare;
                group = groupItems.computeIfAbsent(label, key -> {
                    TreeItem<String> item = new TreeItem<>(key);
                    item.setExpanded(true);
                    root.getChildren().add(item);
                    return item;
                });
                if (vanilla) {
                    group.setExpanded(true);
                }
            }
            group.getChildren().add(new TreeItem<>(nodeLabel(sector)));
        }
        return root;
    }

    /** The planet a sector declares, or the game's default (serpulo). */
    private String sectorPlanet(ContentRecord sector) {
        JsonElement value = sector.fields.get("planet");
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || value.getAsString().isBlank()
            ? "serpulo"
            : value.getAsString();
    }

    private boolean registryVanilla(String name) {
        return controller.registry().isVanillaName(ContentType.planet, name);
    }

    private String nodeLabel(ContentRecord record) {
        return controller.displayName(record) + (record.patch ? " (" + Messages.t("inspector.patch") + ")" : "");
    }

    private ContentRecord recordFor(TreeItem<String> item) {
        if (project == null || item.getValue() == null) {
            return null;
        }
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                continue;
            }
            if (record.type != ContentType.planet && record.type != ContentType.sector) {
                continue;
            }
            if (nodeLabel(record).equals(item.getValue())) {
                return record;
            }
        }
        return null;
    }

    /** Flat record names shown in the tree, prefixed "planet:"/"sector:"/"group:" (test hook). */
    List<String> flattenedTree() {
        List<String> out = new ArrayList<>();
        collect(tree.getRoot(), out);
        return out;
    }

    /** Preview hooks (tests). */
    boolean previewRendered() {
        return preview.rendered();
    }

    int previewCapacity() {
        return preview.previewCapacity();
    }

    int previewOccupiedCount() {
        return preview.previewOccupiedCount();
    }

    boolean previewAppearanceRendered() {
        return preview.appearanceRendered();
    }

    int previewSystemBodies() {
        return preview.systemBodies();
    }

    /** Selects a preview workbench tab (test hook): 0 grid, 1 appearance, 2 system. */
    void selectPreviewTab(int index) {
        preview.selectPreviewTab(index);
    }

    /** Counts non-background pixels on a preview tab (test hook). */
    long previewNonBackgroundPixels(int tabIndex) {
        return preview.nonBackgroundPixels(tabIndex);
    }

    private void collect(TreeItem<String> item, List<String> out) {
        for (TreeItem<String> child : item.getChildren()) {
            ContentRecord record = recordFor(child);
            out.add((record == null ? "group: " : record.type == ContentType.planet ? "planet: " : "sector: ")
                + child.getValue());
            collect(child, out);
        }
    }
}
