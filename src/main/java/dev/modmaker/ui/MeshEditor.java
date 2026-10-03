package dev.modmaker.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.MeshTypes;
import dev.modmaker.core.json.Json;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Structured sub-editor for planet "mesh" / "cloudMesh" objects. Only the mesh types the game can
 * parse are offered, and each type shows exactly the fixed fields its constructor reads - values the
 * user never touches stay out of the JSON and keep the parser's defaults.
 */
final class MeshEditor extends VBox {

    private static final int MAX_DEPTH = 2;

    private final int depth;
    private final String defaultType;
    private final boolean removable;
    private final Consumer<JsonObject> onChange;
    private Runnable onRemove;
    private JsonObject mesh;

    MeshEditor(JsonObject initial, String defaultType, boolean removable, int depth,
        Consumer<JsonObject> onChange) {
        this.defaultType = defaultType;
        this.removable = removable;
        this.depth = depth;
        this.onChange = onChange;
        setSpacing(4);
        setMesh(initial);
    }

    /** Rebinds the editor to a new object (or null = unset). */
    void setMesh(JsonObject initial) {
        this.mesh = initial;
        rebuild();
    }

    /** The mesh type currently being edited, or null when unset. */
    String currentType() {
        return mesh == null ? null
            : mesh.get("type") == null ? "NoiseMesh" : mesh.get("type").getAsString();
    }

    JsonObject currentValue() {
        return mesh;
    }

    /** Test hook: writes a numeric field exactly like the number editor does. */
    void editNumber(String name, double value) {
        ensure();
        mesh.addProperty(name, value);
        rebuild();
        changed();
    }

    private void ensure() {
        if (mesh == null) {
            mesh = new JsonObject();
            mesh.addProperty("type", defaultType);
        }
    }

    private void changed() {
        onChange.accept(mesh);
    }

    private void removed() {
        mesh = null;
        if (onRemove != null) {
            onRemove.run();
        }
        rebuild();
    }

    private void rebuild() {
        getChildren().clear();

        if (mesh == null) {
            Button add = new Button(Messages.t("mesh.add"));
            add.getStyleClass().add("tool-button");
            add.setOnAction(event -> {
                ensure();
                changed();
                rebuild();
            });
            getChildren().add(add);
            return;
        }

        String currentType = currentType();

        // Header: type selector + optional remove.
        ComboBox<String> typeBox = new ComboBox<>();
        for (MeshTypes.Spec spec : MeshTypes.ALL) {
            typeBox.getItems().add(spec.type());
        }
        typeBox.setValue(MeshTypes.PARSEABLE.contains(currentType) ? currentType : defaultType);
        typeBox.valueProperty().addListener((observable, was, value) -> {
            if (value == null || value.equals(currentType())) {
                return;
            }
            // Field sets differ per type; switching starts a fresh object of that type.
            mesh = new JsonObject();
            mesh.addProperty("type", value);
            changed();
            rebuild();
        });
        HBox header = new HBox(6, typeBox);
        header.setAlignment(Pos.CENTER_LEFT);
        if (removable) {
            Button remove = new Button(Messages.t("mesh.remove"));
            remove.getStyleClass().add("tool-button");
            remove.setOnAction(event -> removed());
            header.getChildren().add(remove);
        }
        getChildren().add(header);

        MeshTypes.Spec spec = MeshTypes.spec(currentType);
        if (spec == null) {
            // Unknown type: keep the object editable as raw JSON so nothing is lost.
            getChildren().add(rawEditor(mesh, value -> {
                mesh = value;
                changed();
                rebuild();
            }));
            return;
        }

        VBox rows = new VBox(4);
        for (MeshTypes.Field field : spec.fields()) {
            rows.getChildren().add(fieldRow(field, mesh.get(field.name())));
        }
        getChildren().add(rows);

        if ("MultiMesh".equals(currentType)) {
            rows.getChildren().add(meshesList());
        }
        if ("MatMesh".equals(currentType)) {
            rows.getChildren().add(matSection());
        }
    }

    private Region fieldRow(MeshTypes.Field field, JsonElement current) {
        Label label = new Label(Labels.field(field.name()));
        label.getStyleClass().add("field-key");
        label.setMinWidth(110);
        Node editor = switch (field.type()) {
            case color -> colorEditor(current, value -> write(field, value));
            case textArray -> arrayEditor(current, value -> write(field, value));
            default -> numberOrTextEditor(field, current, value -> write(field, value));
        };
        HBox row = new HBox(8, label, editor);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void write(MeshTypes.Field field, JsonElement value) {
        ensure();
        if (value == null) {
            mesh.remove(field.name());
        } else {
            mesh.add(field.name(), value);
        }
        changed();
    }

    private Node numberOrTextEditor(MeshTypes.Field field, JsonElement current,
        Consumer<JsonElement> commit) {
        TextField field2 = new TextField(current == null || current.isJsonNull()
            ? "" : current.getAsString());
        field2.setPromptText(MeshTypes.PARSEABLE.contains(currentType()) ? "默认" : "");
        field2.setOnAction(event -> commit.accept(parseScalar(field2.getText())));
        field2.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                commit.accept(parseScalar(field2.getText()));
            }
        });
        HBox.setHgrow(field2, Priority.ALWAYS);
        return field2;
    }

    /** Empty text -> remove the key (parser defaults apply); numbers stay numbers, rest is text. */
    private static JsonElement parseScalar(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.matches("-?\\d+")) {
            return new JsonPrimitive(Long.parseLong(trimmed));
        }
        if (trimmed.matches("-?\\d*\\.\\d+([eE][+-]?\\d+)?")) {
            return new JsonPrimitive(Double.parseDouble(trimmed));
        }
        if (trimmed.equals("true") || trimmed.equals("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(trimmed));
        }
        return new JsonPrimitive(trimmed);
    }

    private Node colorEditor(JsonElement current, Consumer<JsonElement> commit) {
        String text = current == null || current.isJsonNull() ? "" : current.getAsString();
        javafx.scene.control.ColorPicker picker = new javafx.scene.control.ColorPicker();
        try {
            picker.setValue(text.isBlank() ? javafx.scene.paint.Color.WHITE
                : javafx.scene.paint.Color.web(text.startsWith("#") ? text : "#" + text));
        } catch (RuntimeException ignored) {
            picker.setValue(javafx.scene.paint.Color.WHITE);
        }
        TextField hex = new TextField(text);
        hex.setPrefWidth(90);
        picker.setOnAction(event -> {
            var c = picker.getValue();
            String formatted = String.format("#%02x%02x%02x",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
            hex.setText(formatted);
            commit.accept(new JsonPrimitive(formatted));
        });
        hex.setOnAction(event -> commit.accept(new JsonPrimitive(hex.getText().trim())));
        hex.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                commit.accept(new JsonPrimitive(hex.getText().trim()));
            }
        });
        HBox box = new HBox(6, picker, hex);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node arrayEditor(JsonElement current, Consumer<JsonElement> commit) {
        TextField field = new TextField(textArrayText(current));
        field.setPromptText("a, b, c");
        field.setOnAction(event -> commit.accept(parseArray(field.getText())));
        field.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                commit.accept(parseArray(field.getText()));
            }
        });
        return field;
    }

    private static String textArrayText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(element -> parts.add(
                element.isJsonPrimitive() ? element.getAsString() : element.toString()));
        } else if (value.isJsonPrimitive()) {
            parts.add(value.getAsString());
        }
        return String.join(", ", parts);
    }

    private static JsonElement parseArray(String text) {
        JsonArray array = new JsonArray();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                array.add(new JsonPrimitive(trimmed));
            }
        }
        return array.size() == 0 ? null : array;
    }

    // --- MultiMesh children ---------------------------------------------------------------------

    private VBox meshesList() {
        VBox list = new VBox(4);
        JsonArray meshes = mesh.has("meshes") && mesh.get("meshes").isJsonArray()
            ? mesh.getAsJsonArray("meshes")
            : new JsonArray();

        for (int i = 0; i < meshes.size(); i++) {
            JsonElement element = meshes.get(i);
            JsonObject child = element != null && element.isJsonObject()
                ? element.getAsJsonObject() : new JsonObject();
            int index = i;
            Button remove = new Button("×");
            remove.getStyleClass().add("tool-button");
            remove.setOnAction(event -> {
                meshes.remove(index);
                changed();
                rebuild();
            });
            HBox row = new HBox(6, new MeshEditor(child, "NoiseMesh", false, depth + 1,
                value -> {
                    if (value == null) {
                        meshes.remove(index);
                    } else {
                        meshes.set(index, value);
                    }
                    changed();
                    rebuild();
                }), remove);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }

        Button addChild = new Button(Messages.t("mesh.addChild"));
        addChild.getStyleClass().add("tool-button");
        addChild.setOnAction(event -> {
            JsonObject child = new JsonObject();
            child.addProperty("type", "HexSkyMesh");
            meshes.add(child);
            changed();
            rebuild();
        });
        list.getChildren().add(addChild);
        return list;
    }

    // --- MatMesh --------------------------------------------------------------------------------

    private VBox matSection() {
        VBox box = new VBox(4);
        Label matLabel = new Label(Messages.t("mesh.mat"));
        matLabel.getStyleClass().add("field-key");
        TextArea area = new TextArea(mesh.has("mat") ? mesh.get("mat").toString() : "");
        area.setPromptText("[1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1]");
        area.setPrefRowCount(2);
        area.focusedProperty().addListener((observable, was, focused) -> {
            if (focused) {
                return;
            }
            try {
                JsonElement parsed = area.getText().isBlank() ? null : Json.parse(area.getText());
                if (parsed == null) {
                    mesh.remove("mat");
                } else {
                    mesh.add("mat", parsed);
                }
                changed();
            } catch (RuntimeException ignored) {
                // keep the text; the user can fix it
            }
        });
        box.getChildren().addAll(matLabel, area);

        JsonObject nested = mesh.has("mesh") && mesh.get("mesh").isJsonObject()
            ? mesh.getAsJsonObject("mesh") : new JsonObject();
        box.getChildren().add(new MeshEditor(nested, "NoiseMesh", false, depth + 1,
            value -> {
                if (value == null) {
                    mesh.remove("mesh");
                } else {
                    mesh.add("mesh", value);
                }
                changed();
                rebuild();
            }));
        return box;
    }

    private Node rawEditor(JsonObject current, Consumer<JsonObject> commit) {
        TextArea area = new TextArea(current == null ? "{}" : current.toString());
        area.setPrefRowCount(4);
        Button apply = new Button(Messages.t("inspector.set"));
        apply.setOnAction(event -> {
            try {
                commit.accept(Json.parseObject(area.getText()));
                rebuild();
            } catch (RuntimeException ignored) {
                // invalid JSON: keep editing
            }
        });
        return new VBox(4, area, apply);
    }
}
