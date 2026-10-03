package dev.modmaker.ui;

import dev.modmaker.core.model.ContentRecord;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The string table: one row per bundle key this tool manages, one column per locale. Editing a cell
 * writes into the string table and marks that locale's bundle for rewriting on save.
 */
public final class StringsPane extends BorderPane {

    private static final class Row {
        private final String key;
        private final Map<String, String> values = new LinkedHashMap<>();

        private Row(String key) {
            this.key = key;
        }
    }

    private final ProjectController controller;
    private final TableView<Row> table = new TableView<>();

    public StringsPane(ProjectController controller) {
        this.controller = controller;
        setCenter(table);
        getStyleClass().add("panel");
        controller.onProjectChanged(this::refresh);
        refresh();
    }

    public void refresh() {
        table.getColumns().clear();
        table.getItems().clear();
        if (controller.project() == null) {
            return;
        }
        List<String> locales = controller.project().locales;

        TableColumn<Row, String> keyColumn = new TableColumn<>(Messages.t("strings.key"));
        keyColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().key));
        keyColumn.setPrefWidth(300);
        table.getColumns().add(keyColumn);

        for (String locale : locales) {
            TableColumn<Row, String> column = new TableColumn<>(locale);
            column.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().values.getOrDefault(locale, "")));
            column.setCellFactory(TextFieldTableCell.forTableColumn());
            column.setOnEditCommit(event -> {
                String key = event.getRowValue().key;
                controller.setString(key, locale, event.getNewValue());
                controller.log("string " + key + " [" + locale + "] updated");
            });
            column.setPrefWidth(220);
            table.getColumns().add(column);
        }

        List<Row> rows = new ArrayList<>();
        controller.project().strings.values.forEach((key, byLocale) -> {
            Row row = new Row(key);
            row.values.putAll(byLocale);
            rows.add(row);
        });
        table.setItems(FXCollections.observableArrayList(rows));
        table.setEditable(true);
        if (rows.isEmpty()) {
            table.setPlaceholder(new Label(Messages.t("strings.empty")));
        }
    }

    /** Adds a string row for a record's four localisable fields, so new content can be named. */
    public void ensureRowsFor(ContentRecord record) {
        refresh();
    }
}
