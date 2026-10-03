package dev.modmaker.ui;

import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The sprite tab: the working environment for sprite work - every sprite asset of the project with a
 * live PNG preview, the atlas region the game will pack it as, and the content records still missing
 * their sprite. Importing and per-card previews land here later.
 */
public final class SpritePane extends BorderPane {

    private final ProjectController controller;
    private final TextField filter = new TextField();
    private final ListView<String> files = new ListView<>();
    private final ImageView preview = new ImageView();
    private final Label info = new Label();
    private final ListView<ContentRecord> missing = new ListView<>();
    private ModProject project;

    public SpritePane(ProjectController controller) {
        this.controller = controller;
        getStyleClass().add("panel");

        filter.setPromptText(Messages.t("sprite.filter"));
        filter.textProperty().addListener((observable, was, now) -> refreshFileList());
        files.getSelectionModel().selectedItemProperty().addListener((observable, was, path) -> show(path));

        preview.setPreserveRatio(true);
        preview.setFitWidth(440);
        preview.setFitHeight(320);
        info.getStyleClass().add("muted");
        info.setWrapText(true);

        missing.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ContentRecord record, boolean empty) {
                super.updateItem(record, empty);
                if (empty || record == null) {
                    setText(null);
                    return;
                }
                setText("[" + Messages.t("ctype." + record.type.name()) + "] " + record.name);
            }
        });
        missing.getSelectionModel().selectedItemProperty().addListener((observable, was, record) -> {
            if (record != null) {
                controller.select(record);
            }
        });

        Label missingTitle = new Label(Messages.t("sprite.missing"));
        missingTitle.getStyleClass().add("section-title");

        files.setPrefWidth(260);
        setLeft(new VBox(6, filter, files));
        setCenter(new VBox(8, preview, info));
        setRight(new VBox(6, missingTitle, missing));
        HBox.setHgrow(files, Priority.ALWAYS);

        controller.onProjectChanged(this::refresh);
        refresh();
    }

    public void refresh() {
        project = controller.project();
        refreshFileList();
        refreshMissing();
    }

    private void refreshFileList() {
        files.getItems().clear();
        preview.setImage(null);
        if (project == null) {
            info.setText("");
            return;
        }
        String query = filter.getText() == null ? "" : filter.getText().toLowerCase(Locale.ROOT);
        List<String> paths = new ArrayList<>();
        for (AssetRef asset : project.assets) {
            if (!asset.kind.equals("sprite") && !asset.kind.equals("spriteOverride")) {
                continue;
            }
            if (query.isEmpty() || asset.path.toLowerCase(Locale.ROOT).contains(query)) {
                paths.add(asset.path);
            }
        }
        paths.sort(String::compareTo);
        files.setItems(FXCollections.observableArrayList(paths));
        if (paths.isEmpty()) {
            info.setText(project.assets.isEmpty()
                ? Messages.t("sprite.noAssets")
                : Messages.t("sprite.empty"));
        }
    }

    private void show(String path) {
        info.setText("");
        if (path == null || project == null || project.root == null) {
            return;
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        boolean override = path.startsWith("sprites-override/");
        String region = dev.modmaker.core.fmt.NameRules.spriteRegion(project.internalName(), base, !override);

        try (InputStream in = Files.newInputStream(project.root.resolve(path))) {
            Image image = new Image(in);
            if (image.isError() || image.getWidth() <= 0) {
                info.setText(Messages.t("sprite.loadFail"));
                preview.setImage(null);
                return;
            }
            preview.setImage(image);
            info.setText(Messages.t("sprite.file") + ": " + path + "\n"
                + Messages.t("sprite.region") + ": " + region
                + (override ? " " + Messages.t("sprite.overrideTag") : "") + "\n"
                + Messages.t("sprite.size") + ": " + (int) image.getWidth() + "×" + (int) image.getHeight());
        } catch (Exception failure) {
            preview.setImage(null);
            info.setText(Messages.t("sprite.loadFail") + ": " + failure.getMessage());
        }
    }

    private void refreshMissing() {
        missing.getItems().clear();
        if (project == null) {
            return;
        }
        missing.setItems(FXCollections.observableArrayList(
            SpriteRegions.withoutSprite(project, project.internalName())));
        missing.setPlaceholder(new Label(Messages.t("sprite.none")));
    }

    /** Sprite paths currently listed (test hook). */
    List<String> listedFiles() {
        return List.copyOf(files.getItems());
    }

    /** Names of records listed as missing their sprite (test hook). */
    List<String> missingNames() {
        List<String> names = new ArrayList<>();
        for (ContentRecord record : missing.getItems()) {
            names.add(record.name);
        }
        return names;
    }

    /** Loads the preview image for a path without selecting it (test hook). */
    boolean canLoad(String path) {
        if (project == null || project.root == null) {
            return false;
        }
        try (InputStream in = Files.newInputStream(project.root.resolve(path))) {
            Image image = new Image(in);
            return !image.isError() && image.getWidth() > 0;
        } catch (Exception failure) {
            return false;
        }
    }
}
