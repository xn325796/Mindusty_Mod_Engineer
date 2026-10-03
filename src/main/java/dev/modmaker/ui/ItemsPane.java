package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Workspace;
import dev.modmaker.core.model.ContentRecord;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Comparator;

/**
 * The items &amp; liquids workspace: a management table over those simple assets. Selection drives the
 * shared inspector on the right; the records also keep nodes on the node canvas, because they are the
 * targets of requirement and research edges.
 */
public final class ItemsPane extends BorderPane {

    private final ProjectController controller;
    private final TableView<ContentRecord> table = new TableView<>();

    public ItemsPane(ProjectController controller) {
        this.controller = controller;
        getStyleClass().add("panel");

        table.setPlaceholder(new Label(Messages.t("assets.empty")));
        buildColumns();

        Button addItem = new Button(Messages.t("assets.add.item"));
        addItem.getStyleClass().add("tool-button");
        addItem.setOnAction(event -> controller.addContent(ContentType.item));
        Button addLiquid = new Button(Messages.t("assets.add.liquid"));
        addLiquid.getStyleClass().add("tool-button");
        addLiquid.setOnAction(event -> controller.addContent(ContentType.liquid));
        Button delete = new Button(Messages.t("assets.delete"));
        delete.getStyleClass().add("tool-button");
        delete.setOnAction(event -> {
            ContentRecord selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                controller.deleteContent(selected);
            }
        });

        HBox buttons = new HBox(8, addItem, addLiquid, delete);
        buttons.setAlignment(Pos.CENTER_LEFT);
        setTop(buttons);
        setCenter(table);

        controller.onProjectChanged(this::refresh);
        refresh();
    }

    private void buildColumns() {
        TableColumn<ContentRecord, String> type = new TableColumn<>(Messages.t("assets.col.type"));
        type.setCellValueFactory(data -> new SimpleStringProperty(
            controller.typeName(data.getValue().type)));
        type.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value);
                if (empty || getTableRow() == null) {
                    setStyle("");
                } else {
                    ContentType recordType = getTableRow().getItem() == null
                        ? null
                        : getTableRow().getItem().type;
                    setStyle(recordType == null ? "" : "-fx-text-fill: " + recordType.color() + ";");
                }
            }
        });
        type.setPrefWidth(70);

        TableColumn<ContentRecord, String> display = new TableColumn<>(Messages.t("assets.col.name"));
        display.setCellValueFactory(data -> new SimpleStringProperty(controller.displayName(data.getValue())));
        display.setPrefWidth(160);

        TableColumn<ContentRecord, String> file = new TableColumn<>(Messages.t("assets.col.file"));
        file.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name));
        file.setPrefWidth(150);

        TableColumn<ContentRecord, String> clazz = new TableColumn<>(Messages.t("inspector.class"));
        clazz.setCellValueFactory(data -> new SimpleStringProperty(
            Labels.clazz(controller.classNameOf(data.getValue()))));
        clazz.setPrefWidth(140);

        TableColumn<ContentRecord, String> color = new TableColumn<>(Labels.field("color"));
        color.setCellValueFactory(data -> new SimpleStringProperty(plain(data.getValue())));
        color.setCellFactory(column -> new TableCell<>() {
            private final Region swatch = new Region();
            private final Label text = new Label();
            private final HBox box = new HBox(8, swatch, text);

            {
                swatch.getStyleClass().add("color-swatch");
                swatch.setMinSize(14, 14);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null || value.isBlank()) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                text.setText(value);
                String web = value.startsWith("#") ? value : "#" + value;
                swatch.setStyle("-fx-background-color: " + web
                    + "; -fx-background-radius: 3; -fx-border-color: #3f3f4a; -fx-border-radius: 3;");
                setGraphic(box);
            }
        });
        color.setPrefWidth(110);

        TableColumn<ContentRecord, String> description = new TableColumn<>(Messages.t("field.description"));
        description.setCellValueFactory(data -> new SimpleStringProperty(descriptionOf(data.getValue())));
        description.setPrefWidth(240);

        table.getColumns().setAll(type, display, file, clazz, color, description);
    }

    private String plain(ContentRecord record) {
        var value = record.fields.get("color");
        return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private String descriptionOf(ContentRecord record) {
        String value = controller.stringValue(record, "description", "en");
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() > 60 ? value.substring(0, 57) + "…" : value;
    }

    public void refresh() {
        if (controller.project() == null) {
            table.setItems(FXCollections.observableArrayList());
            return;
        }
        var rows = controller.project().contents.stream()
            .filter(record -> Workspace.of(record.type) == Workspace.assets)
            .sorted(Comparator.comparing((ContentRecord record) -> record.type.name())
                .thenComparing(record -> record.name))
            .toList();
        table.setItems(FXCollections.observableArrayList(rows));
        table.refresh();
    }

    /** Row count currently listed (test hook). */
    int rowCount() {
        return table.getItems().size();
    }
}
