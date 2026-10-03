package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Workspace;
import dev.modmaker.core.io.ExportReport;
import dev.modmaker.core.model.Board;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Dragboard;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The application window: menus, palette, canvas, inspector, bottom tabs and the log. */
public final class AppShell extends BorderPane {

    private final ProjectController controller;
    private final NodeCanvasPane canvas;
    private final RecordInspector inspector;
    private final StringsPane strings;
    private final SpritePane sprites;
    private final IssuesPane issues;
    private final ItemsPane itemsPane;
    private final CosmosPane cosmosPane;
    private javafx.scene.control.TabPane centerTabs;
    private final TextArea logArea = new TextArea();
    private final ComboBox<ContentType> typeBox = new ComboBox<>();
    private final ComboBox<Board> boardBox = new ComboBox<>();
    private final Label status = new Label();
    private javafx.scene.control.MenuItem undoItem;
    private javafx.scene.control.MenuItem redoItem;
    private javafx.scene.control.Button undoButton;
    private javafx.scene.control.Button redoButton;

    public AppShell(ProjectController controller) {
        this.controller = controller;
        this.canvas = new NodeCanvasPane(controller);
        this.inspector = new RecordInspector(controller);
        this.strings = new StringsPane(controller);
        this.sprites = new SpritePane(controller);
        this.issues = new IssuesPane(controller);
        this.itemsPane = new ItemsPane(controller);
        this.cosmosPane = new CosmosPane(controller);

        getStyleClass().add("app-root");
        controller.setLogSink(message -> Platform.runLater(() -> appendLog(message)));
        controller.onProjectChanged(this::refreshProjectState);

        typeBox.getItems().addAll(ContentType.creatable());
        typeBox.setValue(ContentType.item);
        // Show the localised type name; the selected value stays the enum constant.
        javafx.util.Callback<javafx.scene.control.ListView<ContentType>, javafx.scene.control.ListCell<ContentType>> cellFactory =
            lv -> new javafx.scene.control.ListCell<>() {
                @Override
                protected void updateItem(ContentType item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : Messages.t("ctype." + item.name()));
                }
            };
        typeBox.setCellFactory(cellFactory);
        typeBox.setButtonCell(cellFactory.call(null));
        boardBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Board board) {
                return board == null ? "" : board.name;
            }

            @Override
            public Board fromString(String text) {
                return null;
            }
        });
        boardBox.valueProperty().addListener((observable, was, board) -> {
            if (board != null && board != controller.board()) {
                controller.setBoard(board);
            }
        });

        logArea.setEditable(false);
        logArea.getStyleClass().add("log-area");

        rebuild();
        Messages.onChange(this::rebuild);
        refreshProjectState();
    }

    // --- layout ---------------------------------------------------------------------------------

    private void rebuild() {
        setTop(new VBox(buildMenuBar(), buildToolBar()));
        setLeft(buildPalette());
        setCenter(buildCenter());
        setRight(inspector);
        setBottom(new VBox(buildBottom(), buildStatusBar()));
        refreshProjectState();
    }

    /**
     * The main area: one tab per workflow. The node canvas flow-edits blocks and units (and stays
     * the place where reference edges live); items and liquids get an asset table; planets and
     * sectors get an arrangement tree.
     */
    private Region buildCenter() {
        centerTabs = new javafx.scene.control.TabPane(
            workspaceTab(Workspace.canvas, canvas),
            workspaceTab(Workspace.assets, itemsPane),
            workspaceTab(Workspace.cosmos, cosmosPane));
        centerTabs.getStyleClass().add("center-tabs");
        return centerTabs;
    }

    private javafx.scene.control.Tab workspaceTab(Workspace workspace, javafx.scene.Node content) {
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(Messages.t("workspace." + workspace), content);
        tab.setClosable(false);
        return tab;
    }

    /** Brings the workspace tab that edits the given content type to the front. */
    public void showWorkspace(Workspace workspace) {
        if (centerTabs == null) {
            return;
        }
        centerTabs.getTabs().stream()
            .filter(tab -> Messages.t("workspace." + workspace).equals(tab.getText()))
            .findFirst().ifPresent(tab -> centerTabs.getSelectionModel().select(tab));
    }

    private MenuBar buildMenuBar() {
        MenuBar bar = new MenuBar();
        undoItem = action("menu.edit.undo", controller::undo);
        redoItem = action("menu.edit.redo", controller::redo);
        undoItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+Z"));
        redoItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+Y"));
        MenuItem saveItem = action("menu.project.save", this::save);
        saveItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+S"));

        bar.getMenus().addAll(
            menu("menu.project",
                action("menu.project.new", this::newProject),
                action("menu.project.open", this::openProject),
                new SeparatorMenuItem(),
                action("menu.import.mod", this::importZip),
                action("menu.import.folder", this::importFolder),
                new SeparatorMenuItem(),
                saveItem,
                new SeparatorMenuItem(),
                action("menu.project.exit", Platform::exit)),
            menu("menu.edit", undoItem, redoItem),
            menu("menu.build",
                action("toolbar.validate", this::validate),
                new SeparatorMenuItem(),
                action("menu.build.folder", this::buildFolder),
                action("menu.build.zip", this::buildZip),
                new SeparatorMenuItem(),
                action("menu.build.launch", this::launchGame)),
            menu("menu.view",
                action("menu.view.tidyLayout", controller::tidyLayout),
                action("menu.view.fit", canvas::fitToContent),
                action("menu.view.board", this::addBoard)),
            buildLanguageMenu(),
            menu("menu.help", action("menu.help.about", this::about)));
        return bar;
    }

    private Menu buildLanguageMenu() {
        Menu menu = new Menu(Messages.t("menu.language"));
        MenuItem english = new MenuItem("English");
        english.setOnAction(event -> Messages.setLocale(Locale.ENGLISH));
        MenuItem chinese = new MenuItem("简体中文");
        chinese.setOnAction(event -> Messages.setLocale(Locale.SIMPLIFIED_CHINESE));
        menu.getItems().addAll(english, chinese);
        return menu;
    }

    /** Adds content and brings the workspace that edits it to the front. */
    private void addContentRouted(ContentType type) {
        withProject(() -> {
            Workspace workspace = Workspace.of(type);
            if (workspace == Workspace.canvas) {
                Point2D center = canvas.viewportCenterWorld();
                double cascade = canvas.nextCascade();
                controller.addContent(type,
                    center.getX() - ContentCard.WIDTH / 2 + cascade,
                    center.getY() - ContentCard.HEIGHT / 2 + cascade);
            } else {
                controller.addContent(type);
                showWorkspace(workspace);
            }
        });
    }

    private Region buildToolBar() {
        Button add = new Button(Messages.t("toolbar.add"));
        add.getStyleClass().add("tool-button");
        add.setOnAction(event -> addContentRouted(typeBox.getValue()));

        undoButton = toolButton("menu.edit.undo", controller::undo);
        redoButton = toolButton("menu.edit.redo", controller::redo);
        Button tidy = toolButton("toolbar.layout", controller::tidyLayout);
        Button fit = toolButton("toolbar.fit", canvas::fitToContent);
        Button validate = toolButton("toolbar.validate", this::validate);
        Button build = toolButton("toolbar.build", this::buildFolder);
        Button packageZip = toolButton("toolbar.package", this::buildZip);
        Button launch = toolButton("toolbar.launch", this::launchGame);

        typeBox.setPrefWidth(120);
        boardBox.setPrefWidth(140);
        Button newBoard = toolButton("toolbar.newBoard", this::addBoard);

        // Experimental adaptive class switching: the inspector's class dropdown narrows to the
        // same branch of the hierarchy when enabled.
        javafx.scene.control.CheckBox adaptive = new javafx.scene.control.CheckBox(Messages.t("toolbar.adaptive"));
        adaptive.getStyleClass().add("tool-check");
        adaptive.setSelected(controller.adaptiveClasses());
        adaptive.selectedProperty().addListener((observable, was, now) ->
            controller.setAdaptiveClasses(Boolean.TRUE.equals(now)));
        javafx.scene.control.Tooltip.install(adaptive,
            new javafx.scene.control.Tooltip(Messages.t("toolbar.adaptive.tip")));

        HBox bar = new HBox(8, typeBox, add, adaptive, new Separator(Orientation.VERTICAL),
            undoButton, redoButton, new Separator(Orientation.VERTICAL), tidy, fit,
            new Separator(Orientation.VERTICAL), validate, build, packageZip, launch,
            new Separator(Orientation.VERTICAL), boardBox, newBoard);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("tool-bar");
        return bar;
    }

    private Region buildPalette() {
        VBox box = new VBox(2);
        box.getStyleClass().add("panel");
        box.getChildren().add(panelTitle("palette.title"));
        for (ContentType type : ContentType.creatable()) {
            box.getChildren().add(paletteRow(type));
        }
        box.getChildren().addAll(new Separator(), panelTitle("palette.inline"));
        for (ContentType type : ContentType.values()) {
            if (!type.fileBacked()) {
                box.getChildren().add(paletteRow(type));
            }
        }

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefWidth(215);
        scroll.getStyleClass().add("panel-scroll");
        return scroll;
    }

    private Region paletteRow(ContentType type) {
        Region dot = new Region();
        dot.getStyleClass().add("type-dot");
        dot.setStyle("-fx-background-color: " + type.color() + ";");

        Label name = new Label(controller.typeName(type));
        name.getStyleClass().add("palette-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label folder = new Label(type.fileBacked() ? type.folderName() : Messages.t("palette.inline"));
        folder.getStyleClass().add("palette-folder");

        HBox row = new HBox(8, dot, name, spacer, folder);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("palette-row");
        if (type.fileBacked()) {
            row.setOnMouseClicked(event -> addContentRouted(type));
            // Drag a palette row onto the canvas to place the card exactly there.
            row.setOnDragDetected(event -> {
                Dragboard board = row.startDragAndDrop(javafx.scene.input.TransferMode.COPY);
                board.setContent(Map.of(NodeCanvasPane.CONTENT_TYPE_FORMAT, type.name()));
                event.consume();
            });
        }
        return row;
    }

    private Region buildBottom() {
        TabPane tabs = new TabPane(
            tab("bottom.strings", strings),
            tab("bottom.spritesTab", sprites),
            tab("bottom.validation", issues),
            tab("bottom.log", logArea));
        tabs.setPrefHeight(210);
        tabs.getStyleClass().add("bottom-tabs");
        return tabs;
    }

    private Region buildStatusBar() {
        status.getStyleClass().add("status-text");
        HBox bar = new HBox(status);
        bar.setPadding(new Insets(4, 12, 4, 12));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // --- actions --------------------------------------------------------------------------------

    private void newProject() {
        TextInputDialog dialog = new TextInputDialog("my-mod");
        dialog.setHeaderText(Messages.t("dialog.modName"));
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.projectParent"));
        File parent = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (parent == null) {
            return;
        }
        Path root = parent.toPath().resolve(safeFolderName(name.get()));
        runIo(() -> controller.createProject(name.get().trim(), root), false);
    }

    private void openProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.openProject"));
        File folder = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (folder == null) {
            return;
        }
        runIo(() -> controller.openProject(folder.toPath()), true);
    }

    private void importZip() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.t("dialog.importMod"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mindustry mod", "*.zip", "*.jar"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        importInto(file.toPath());
    }

    private void importFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.importMod"));
        File folder = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (folder == null) {
            return;
        }
        importInto(folder.toPath());
    }

    private void importInto(Path source) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.projectParent"));
        File destination = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (destination == null) {
            return;
        }
        runIo(() -> controller.importPackage(source, destination.toPath()), true);
    }

    private void save() {
        runIo(() -> controller.save(), false);
    }

    private void validate() {
        if (controller.project() == null) {
            return;
        }
        var issues = controller.validate();
        appendLog("validation: " + issues.size() + " findings");
        issues.stream().limit(10).forEach(issue -> appendLog("  " + issue.message()));
        selectTab("bottom.validation");
    }

    private void buildFolder() {
        if (controller.project() == null) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.buildFolder"));
        File destination = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (destination == null) {
            return;
        }
        runIo(() -> {
            ExportReport report = controller.buildFolder(destination.toPath());
            appendLog("built " + report.files().size() + " files (" + report.bytes() + " bytes) into "
                + report.target());
        }, false);
        selectTab("bottom.log");
    }

    private void buildZip() {
        if (controller.project() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.t("dialog.buildZip"));
        chooser.setInitialFileName(controller.project().internalName() + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("zip", "*.zip"));
        File file = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        runIo(() -> {
            ExportReport report = controller.buildZip(file.toPath());
            appendLog("packaged " + report.files().size() + " files into " + report.target());
        }, false);
        selectTab("bottom.log");
    }

    private void launchGame() {
        if (controller.project() == null) {
            return;
        }
        Path java = GameLauncher.detectJava();
        Path jar = GameLauncher.detectGameJar();
        if (java == null || jar == null) {
            appendLog("cannot launch: java=" + java + " jar=" + jar
                + " (set JAVA_HOME or place Mindustry.jar next to the project)");
            new Alert(Alert.AlertType.WARNING, Messages.t("dialog.launchUnavailable")).showAndWait();
            return;
        }
        runIo(() -> {
            try {
                GameLauncher.Session session = GameLauncher.buildAndLaunch(controller, jar, java,
                    this::appendLog);
                appendLog("test data directory: " + session.dataDir());
            } catch (Exception failure) {
                appendLog("launch failed: " + failure);
            }
        }, false);
        selectTab("bottom.log");
    }

    private void addBoard() {
        if (controller.project() == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("Board " + (controller.project().boards.size() + 1));
        dialog.setHeaderText(Messages.t("dialog.boardName"));
        dialog.showAndWait().filter(name -> !name.isBlank())
            .ifPresent(name -> controller.addBoard(name.trim()));
    }

    private void about() {
        new Alert(Alert.AlertType.INFORMATION,
            "Mindustry ModMaker\n" + Messages.t("about.body")).showAndWait();
    }

    // --- state ----------------------------------------------------------------------------------

    /** The class choices currently shown in the inspector (test hook). */
    List<String> inspectorChoices() {
        return inspector.currentClassChoices();
    }

    /** The sprite pane (test hook). */
    SpritePane spritePane() {
        return sprites;
    }

    /** The cosmos pane (test hook). */
    CosmosPane cosmosPane() {
        return cosmosPane;
    }

    /** Selects a bottom tab by its message key (also used by tests to reach a pane). */
    public void selectTab(String key) {
        Node bottom = getBottom();
        if (bottom instanceof VBox vbox && !vbox.getChildren().isEmpty()
            && vbox.getChildren().get(0) instanceof TabPane tabs) {
            tabs.getTabs().stream()
                .filter(tab -> Messages.t(key).equals(tab.getText()))
                .findFirst().ifPresent(tab -> tabs.getSelectionModel().select(tab));
        }
    }

    /** Called after a project is loaded from the command line. */
    public void justLoaded() {
        canvas.fitToContent();
        refreshProjectState();
    }

    private void refreshProjectState() {
        if (!boardBox.getItems().isEmpty()) {
            boardBox.getItems().clear();
        }
        if (controller.project() == null) {
            status.setText(Messages.t("status.noProject"));
            boardBox.setDisable(true);
            return;
        }
        boardBox.setDisable(false);
        boardBox.getItems().addAll(controller.project().boards);
        boardBox.setValue(controller.board());
        boolean canUndo = controller.canUndo();
        boolean canRedo = controller.canRedo();
        if (undoItem != null) {
            undoItem.setDisable(!canUndo);
        }
        if (redoItem != null) {
            redoItem.setDisable(!canRedo);
        }
        if (undoButton != null) {
            undoButton.setDisable(!canUndo);
        }
        if (redoButton != null) {
            redoButton.setDisable(!canRedo);
        }
        status.setText(controller.project().displayName()
            + "  ·  " + controller.project().contents.size() + " " + Messages.t("status.content")
            + "  ·  " + controller.project().assets.size() + " " + Messages.t("status.assets")
            + "  ·  " + controller.project().root);
    }

    private void appendLog(String message) {
        logArea.appendText(message + "\n");
    }

    private void withProject(Runnable action) {
        if (controller.project() == null) {
            appendLog("open a project first");
            return;
        }
        action.run();
    }

    private interface IoAction {
        void run() throws Exception;
    }

    private void runIo(IoAction action, boolean fitAfter) {
        try {
            action.run();
        } catch (Exception failure) {
            appendLog("error: " + failure);
            new Alert(Alert.AlertType.ERROR, String.valueOf(failure.getMessage())).showAndWait();
        }
        refreshProjectState();
        if (fitAfter) {
            canvas.fitToContent();
        }
    }

    // --- small helpers --------------------------------------------------------------------------

    private Menu menu(String key, MenuItem... items) {
        Menu menu = new Menu(Messages.t(key));
        menu.getItems().addAll(items);
        return menu;
    }

    private MenuItem action(String key, Runnable runnable) {
        MenuItem item = new MenuItem(Messages.t(key));
        item.setOnAction(event -> {
            try {
                runnable.run();
            } catch (RuntimeException failure) {
                appendLog("error: " + failure);
            }
        });
        return item;
    }

    private Button toolButton(String key, Runnable runnable) {
        Button button = new Button(Messages.t(key));
        button.getStyleClass().add("tool-button");
        button.setOnAction(event -> {
            try {
                runnable.run();
            } catch (RuntimeException failure) {
                appendLog("error: " + failure);
            }
        });
        return button;
    }

    private Tab tab(String key, Node content) {
        Tab tab = new Tab(Messages.t(key), content);
        tab.setClosable(false);
        return tab;
    }

    private Label panelTitle(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("panel-title");
        return label;
    }

    private static String safeFolderName(String name) {
        String cleaned = name.trim().toLowerCase(Locale.ROOT).replace(' ', '-')
            .replaceAll("[\\\\/:*?\"<>|]", "");
        return cleaned.isEmpty() ? "my-mod" : cleaned;
    }

    /** Unused guard kept for symmetry with Files-based checks in tests. */
    static boolean isDirectory(Path path) {
        return path != null && Files.isDirectory(path);
    }
}
