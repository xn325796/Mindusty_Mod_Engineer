package dev.modmaker.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Schema-driven editor for the selected record: identity, localised strings, then one editor per
 * field the schema knows, and a raw JSON area for whatever it does not.
 *
 * <p>Edits go through the controller, which marks the record dirty so the file is re-serialised on
 * save; untouched records keep their original bytes.
 */
public final class RecordInspector extends ScrollPane {

    private final ProjectController controller;
    private final VBox content = new VBox(8);

    public RecordInspector(ProjectController controller) {
        this.controller = controller;
        setFitToWidth(true);
        setPrefWidth(360);
        getStyleClass().add("panel-scroll");
        setContent(content);
        controller.onSelectionChanged(this::rebuild);
        controller.onProjectChanged(() -> rebuild(controller.selected()));
        rebuild(null);
    }

    private void rebuild(ContentRecord record) {
        content.getChildren().clear();
        if (record == null || controller.project() == null) {
            content.getChildren().addAll(
                title("inspector.title"),
                muted("inspector.empty"));
            return;
        }

        content.getChildren().addAll(header(record), new Separator(), identity(record),
            new Separator(), localisation(record), new Separator(), fields(record),
            new Separator(), unknownFields(record));
    }

    // --- sections -------------------------------------------------------------------------------

    /** The class choices currently offered by the identity section (for tests and tooling). */
    List<String> currentClassChoices() {
        return lastClassChoices == null ? List.of() : List.copyOf(lastClassChoices);
    }

    private List<String> lastClassChoices;

    private Region header(ContentRecord record) {
        Label name = new Label(controller.displayName(record));
        name.getStyleClass().add("card-title");
        Label meta = new Label(controller.subtitleOf(record)
            + (record.isInline() ? " · inline" : "") + (record.patch ? " · patch" : ""));
        meta.getStyleClass().add("card-sub");
        Label file = new Label(record.isInline()
            ? Messages.t("inspector.inlineIn", ownerPath(record), record.inlinePath)
            : (record.rawPath == null ? Messages.t("inspector.newFile") : record.rawPath));
        file.getStyleClass().add("muted");
        file.setWrapText(true);
        return new VBox(2, name, meta, file);
    }

    private Region identity(ContentRecord record) {
        VBox box = new VBox(6, sectionTitle("identity"));
        TextField nameField = new TextField(record.name);
        nameField.setOnAction(event -> {
            controller.rename(record, nameField.getText().trim());
            rebuild(controller.selected());
        });
        nameField.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                controller.rename(record, nameField.getText().trim());
            }
        });
        box.getChildren().add(row(Messages.t("inspector.name"), nameField));

        ComboBox<String> classBox = new ComboBox<>();
        classBox.setEditable(true);
        // Experimental: with adaptive classes on, the dropdown only offers the same branch of the
        // hierarchy (a battery cannot become an item bridge). Typing still allows anything.
        List<String> classes = controller.adaptiveClasses()
            ? controller.registry().relatedClasses(record.type, controller.classNameOf(record))
            : controller.registry().classesForType(record.type);
        String currentClass = controller.classNameOf(record);
        if (!classes.contains(currentClass)) {
            classes = new ArrayList<>(classes);
            classes.add(0, currentClass);
        }
        lastClassChoices = classes;
        classBox.getItems().addAll(classes.isEmpty() ? List.of(currentClass) : classes);
        // Editable combo boxes render the button through the converter, not the button cell:
        // show the translated label, and accept either the label or the raw class name as input.
        classBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : Labels.clazz(value);
            }

            @Override
            public String fromString(String text) {
                if (text == null) {
                    return null;
                }
                for (String item : classBox.getItems()) {
                    if (item.equals(text) || Labels.clazz(item).equals(text)) {
                        return item;
                    }
                }
                return text;
            }
        });
        classBox.setValue(controller.classNameOf(record));
        classBox.valueProperty().addListener((observable, was, value) -> {
            if (value == null) {
                return;
            }
            String base = controller.registry().baseClassOf(record.type);
            // The game assumes the base class, so writing it out would be noise.
            controller.setField(record, "type", value.equals(base) ? null : new JsonPrimitive(value));
            rebuild(controller.selected());
        });
        box.getChildren().add(row(Messages.t("inspector.class"), classBox));

        CheckBox patch = new CheckBox(Messages.t("inspector.patch"));
        patch.setSelected(record.patch);
        patch.setDisable(record.isInline());
        patch.selectedProperty().addListener((observable, was, value) -> {
            record.patch = value;
            record.dirty = true;
        });
        box.getChildren().add(patch);

        if (record.isInline()) {
            Label note = new Label(Messages.t("inspector.inlineIn", ownerPath(record), record.inlinePath));
            note.getStyleClass().add("muted");
            note.setWrapText(true);
            box.getChildren().add(note);
        }
        return box;
    }

    private Region localisation(ContentRecord record) {
        VBox box = new VBox(6, sectionTitle("localisation"));
        if (record.isInline()) {
            box.getChildren().add(muted("inspector.notLocalised"));
            return box;
        }
        for (String field : NameRules.BUNDLE_FIELDS) {
            String key = controller.stringKey(record, field);
            String fieldLabel = Messages.t("field." + field);
            for (String locale : controller.project().locales) {
                TextField editor = new TextField(controller.stringValue(record, field, locale));
                editor.setPromptText(fieldLabel);
                editor.setOnAction(event -> {
                    controller.setString(key, locale, editor.getText());
                    controller.log("string " + key + " [" + locale + "] updated");
                });
                editor.focusedProperty().addListener((observable, was, focused) -> {
                    if (!focused) {
                        controller.setString(key, locale, editor.getText());
                    }
                });
                box.getChildren().add(row(fieldLabel + " [" + locale + "]", editor));
            }
        }
        return box;
    }

    private Region fields(ContentRecord record) {
        VBox box = new VBox(6, sectionTitle("fields"));
        String className = controller.classNameOf(record);
        List<FieldDef> definitions = controller.registry().fieldsOf(className);
        if (definitions.isEmpty()) {
            box.getChildren().add(muted("inspector.noSchema"));
            return box;
        }
        // Make the adaptive behaviour visible: these are the fields of the selected class.
        Label source = new Label(Messages.t("inspector.fieldsFor", Labels.clazz(className)));
        source.getStyleClass().add("muted");
        source.setWrapText(true);
        box.getChildren().add(source);
        for (FieldDef definition : definitions) {
            if (definition.name().equals("type") || definition.name().equals("name")
                || definition.name().equals("description") || definition.name().equals("details")
                || definition.name().equals("credit")) {
                continue;
            }
            box.getChildren().add(editorRow(record, definition));
        }
        box.getChildren().add(addFieldRow(record, definitions));
        return box;
    }

    private Region unknownFields(ContentRecord record) {
        VBox box = new VBox(6, sectionTitle("inspector.raw"));
        TextArea area = new TextArea(Json.write(record.tree()));
        area.getStyleClass().add("raw-area");
        area.setPrefRowCount(10);
        area.setWrapText(false);
        Label hint = new Label(Messages.t("inspector.raw.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        javafx.scene.control.Button apply = new javafx.scene.control.Button(Messages.t("inspector.raw.apply"));
        apply.setOnAction(event -> {
            try {
                var parsed = Json.parseObject(area.getText());
                record.applyTree(parsed);
                record.dirty = true;
                controller.log("applied raw json to '" + record.name + "'");
                rebuild(record);
            } catch (RuntimeException failure) {
                controller.log("raw json is not valid: " + failure.getMessage());
            }
        });
        box.getChildren().addAll(area, hint, apply);
        return box;
    }

    // --- editors --------------------------------------------------------------------------------

    private Region editorRow(ContentRecord record, FieldDef definition) {
        // Meshes get structured sub-editors: the game's parser accepts fixed types with fixed
        // fields (ContentParser.parseMesh), so free-form JSON editing is unnecessary here.
        if (definition.name().equals("mesh") || definition.name().equals("cloudMesh")) {
            return meshRow(record, definition);
        }
        JsonElement value = record.fields.get(definition.name());
        Consumer<JsonElement> commit = next -> {
            controller.setField(record, definition.name(), next);
            rebuild(controller.selected());
        };
        Node editor = switch (definition.type()) {
            case number -> textEditor(plain(value), input -> commit.accept(numberOf(input)), 90);
            case text, sprite -> textEditor(plain(value),
                input -> commit.accept(new JsonPrimitive(input)), 130);
            case textArray -> textEditor(stringArrayText(value),
                input -> commit.accept(arrayOf(input)), 150);
            case bool -> boolEditor(value, commit);
            case color -> colorEditor(value, commit);
            case enumeration -> enumEditor(definition, value, commit);
            case content -> contentEditor(definition, value, commit);
            case stack -> textEditor(stackText(value), input -> commit.accept(stackOf(input)), 130);
            case stackArray -> textEditor(stackArrayText(value),
                input -> commit.accept(stackArrayOf(input)), 150);
            case raw -> rawEditor(value, commit);
        };
        // The row shows the translated label; the raw JSON key stays in the tooltip next to the
        // description, because the raw key is what appears in the exported file.
        Region row = row(Labels.field(definition.name()), editor);
        String tooltip = definition.description() == null || definition.description().isBlank()
            ? definition.name()
            : definition.name() + " — " + definition.description();
        javafx.scene.control.Tooltip.install(row, new javafx.scene.control.Tooltip(tooltip));
        return row;
    }

    /** Structured sub-table for planet mesh / cloudMesh objects. */
    private Region meshRow(ContentRecord record, FieldDef definition) {
        JsonElement current = record.fields.get(definition.name());
        JsonObject initial = current != null && current.isJsonObject()
            ? current.getAsJsonObject() : null;
        String defaultType = definition.name().equals("cloudMesh") ? "MultiMesh" : "NoiseMesh";
        MeshEditor editor = new MeshEditor(initial, defaultType, true, 0,
            value -> controller.setField(record, definition.name(), value));
        return row(Labels.field(definition.name()), editor);
    }

    private Node textEditor(String value, Consumer<String> commit, double width) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPrefWidth(width);
        field.setOnAction(event -> commit.accept(field.getText()));
        field.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                commit.accept(field.getText());
            }
        });
        return field;
    }

    private Node boolEditor(JsonElement value, Consumer<JsonElement> commit) {
        CheckBox box = new CheckBox();
        box.setSelected(value != null && value.isJsonPrimitive() && value.getAsBoolean());
        box.selectedProperty().addListener((observable, was, now) -> commit.accept(new JsonPrimitive(now)));
        return box;
    }

    private Node colorEditor(JsonElement value, Consumer<JsonElement> commit) {
        String text = plain(value);
        ColorPicker picker = new ColorPicker();
        try {
            picker.setValue(text == null || text.isBlank() ? Color.WHITE : Color.web(text.startsWith("#") ? text : "#" + text));
        } catch (RuntimeException ignored) {
            picker.setValue(Color.WHITE);
        }
        TextField hex = new TextField(text == null ? "" : text);
        hex.setPrefWidth(80);
        picker.setOnAction(event -> {
            String formatted = String.format("#%02x%02x%02x",
                Math.round(picker.getValue().getRed() * 255),
                Math.round(picker.getValue().getGreen() * 255),
                Math.round(picker.getValue().getBlue() * 255));
            commit.accept(new JsonPrimitive(formatted));
        });
        hex.setOnAction(event -> commit.accept(new JsonPrimitive(hex.getText().trim())));
        HBox box = new HBox(6, picker, hex);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node enumEditor(FieldDef definition, JsonElement value, Consumer<JsonElement> commit) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().addAll(definition.enumValues());
        box.setEditable(true);
        box.setValue(plain(value));
        box.valueProperty().addListener((observable, was, now) -> {
            if (now != null) {
                commit.accept(new JsonPrimitive(now));
            }
        });
        return box;
    }

    private Node contentEditor(FieldDef definition, JsonElement value,
        Consumer<JsonElement> commit) {
        ComboBox<String> box = new ComboBox<>();
        box.setEditable(true);
        box.getItems().addAll(referenceCandidates(definition));
        box.setValue(plain(value));
        box.valueProperty().addListener((observable, was, now) -> {
            if (now != null) {
                commit.accept(new JsonPrimitive(now));
            }
        });
        return box;
    }

    private Node rawEditor(JsonElement value, Consumer<JsonElement> commit) {
        TextArea area = new TextArea(value == null ? "" : Json.write(value));
        area.setPrefRowCount(4);
        area.getStyleClass().add("raw-area");
        javafx.scene.control.Button applyButton = new javafx.scene.control.Button(Messages.t("inspector.set"));
        applyButton.setOnAction(event -> {
            try {
                commit.accept(area.getText().isBlank() ? null : Json.parse(area.getText()));
            } catch (RuntimeException failure) {
                controller.log("value is not valid JSON: " + failure.getMessage());
            }
        });
        VBox box = new VBox(4, area, applyButton);
        return box;
    }

    private Region addFieldRow(ContentRecord record, List<FieldDef> definitions) {
        Map<String, FieldDef> known = new LinkedHashMap<>();
        definitions.forEach(definition -> known.put(definition.name(), definition));
        TextField fieldName = new TextField();
        fieldName.setPromptText(Messages.t("inspector.addField.name"));
        fieldName.setPrefWidth(120);
        TextField fieldValue = new TextField();
        fieldValue.setPromptText(Messages.t("inspector.addField.value"));
        HBox.setHgrow(fieldValue, Priority.ALWAYS);
        javafx.scene.control.Button add = new javafx.scene.control.Button(Messages.t("inspector.add"));
        add.setOnAction(event -> {
            String name = fieldName.getText().trim();
            if (name.isEmpty()) {
                return;
            }
            String raw = fieldValue.getText();
            JsonElement parsed;
            try {
                parsed = raw.isBlank() ? new JsonPrimitive("") : Json.parse(raw);
            } catch (RuntimeException failure) {
                parsed = new JsonPrimitive(raw);
            }
            controller.setField(record, name, parsed);
            rebuild(record);
        });
        HBox box = new HBox(6, fieldName, fieldValue, add);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Reference candidates: this project's content first, then the game's own names. */
    private List<String> referenceCandidates(FieldDef definition) {
        List<String> candidates = new ArrayList<>();
        String wanted = definition.contentCtype();
        String internal = controller.project().internalName();
        for (ContentRecord record : controller.project().contents) {
            if (record.isInline() || !record.type.fileBacked()) {
                continue;
            }
            if (wanted != null && !wanted.equals("any") && !record.type.name().equals(wanted)) {
                continue;
            }
            candidates.add(record.fullName(internal));
        }
        for (ContentType type : ContentType.values()) {
            if (wanted != null && !wanted.equals("any") && !type.name().equals(wanted)) {
                continue;
            }
            controller.registry().vanillaNames(type).stream().limit(120).forEach(candidates::add);
        }
        return candidates.stream().distinct().limit(400).toList();
    }

    // --- small helpers --------------------------------------------------------------------------

    private String ownerPath(ContentRecord record) {
        ContentRecord owner = controller.project().contentById(record.inlineOwnerId);
        return owner == null ? "(missing)" : (owner.rawPath == null ? owner.name : owner.rawPath);
    }

    private static String plain(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : Json.write(value);
    }

    private static String stringArrayText(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return plain(value);
        }
        List<String> parts = new ArrayList<>();
        value.getAsJsonArray().forEach(element -> parts.add(plain(element)));
        return String.join(", ", parts);
    }

    private static JsonElement arrayOf(String text) {
        JsonArray array = new JsonArray();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                array.add(new JsonPrimitive(trimmed));
            }
        }
        return array.isEmpty() ? null : array;
    }

    private static String stackText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        JsonElement target = value.getAsJsonObject().get("item") != null
            ? value.getAsJsonObject().get("item")
            : value.getAsJsonObject().get("liquid");
        JsonElement amount = value.getAsJsonObject().get("amount");
        return target == null ? "" : target.getAsString() + "/" + (amount == null ? "1" : amount.getAsString());
    }

    private static JsonElement stackOf(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : new JsonPrimitive(trimmed);
    }

    private static String stackArrayText(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return stackText(value);
        }
        List<String> parts = new ArrayList<>();
        value.getAsJsonArray().forEach(element -> parts.add(stackText(element)));
        return String.join(", ", parts);
    }

    private static JsonElement stackArrayOf(String text) {
        JsonArray array = new JsonArray();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                array.add(new JsonPrimitive(trimmed));
            }
        }
        return array.isEmpty() ? null : array;
    }

    private static JsonElement numberOf(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return trimmed.contains(".") ? new JsonPrimitive(Double.parseDouble(trimmed))
                : new JsonPrimitive(Long.parseLong(trimmed));
        } catch (NumberFormatException failure) {
            return new JsonPrimitive(trimmed);
        }
    }

    private Label title(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("panel-title");
        return label;
    }

    private Label sectionTitle(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("section-title");
        return label;
    }

    private Label muted(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        return label;
    }

    private Region row(String label, Node editor) {
        Label key = new Label(label);
        key.getStyleClass().add("field-key");
        key.setMinWidth(96);
        HBox.setHgrow(editor, Priority.ALWAYS);
        HBox box = new HBox(8, key, editor);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("field-row");
        box.setPadding(new Insets(4, 8, 4, 8));
        return box;
    }
}
