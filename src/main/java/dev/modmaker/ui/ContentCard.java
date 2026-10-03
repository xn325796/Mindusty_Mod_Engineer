package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One content record drawn as a card: type colour strip, localised name, class line and a short
 * field preview.
 *
 * <p>Ports: the right circle is this content's ID (what other content can reference); the left
 * circles are its reference fields (what this content points at elsewhere). Port anchors are
 * card-relative constants, so geometry never depends on a layout pass.
 */
final class ContentCard extends StackPane {

    static final double WIDTH = 260;
    static final double HEIGHT = 130;
    static final double PORT_RADIUS = 5;
    static final int MAX_INPUT_PORTS = 4;

    /** One draggable/hittable connection point. {@code fieldKey == null} is the ID output port. */
    static final class Port {
        final String nodeId;
        final String fieldKey;
        /** Anchor relative to the card's top-left corner. */
        final double x;
        final double y;
        final Circle circle;

        Port(String nodeId, String fieldKey, double x, double y, Circle circle) {
            this.nodeId = nodeId;
            this.fieldKey = fieldKey;
            this.x = x;
            this.y = y;
            this.circle = circle;
        }
    }

    private final List<Port> ports = new ArrayList<>();
    private final Label title = new Label();
    private final Label subtitleLabel = new Label();
    private final Label badge = new Label();
    private final VBox fieldRows = new VBox(2);

    ContentCard(ContentType type, String displayName, String subtitle, boolean inline,
        boolean patch, List<String> previewFields, List<String> inputFields) {
        getStyleClass().add("card");
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);

        Region strip = new Region();
        strip.getStyleClass().add("type-strip");
        strip.setStyle("-fx-background-color: " + type.color() + ";");
        strip.setMinWidth(6);
        strip.setPrefWidth(6);
        strip.setMaxWidth(6);

        title.setText(displayName);
        title.getStyleClass().add("card-title");
        subtitleLabel.setText(subtitle);
        subtitleLabel.getStyleClass().add("card-sub");

        StringBuilder flags = new StringBuilder();
        if (inline) {
            flags.append("inline ");
        }
        if (patch) {
            flags.append("patch");
        }
        badge.setText(flags.toString().trim());
        badge.getStyleClass().add("card-badge");
        badge.setVisible(!flags.isEmpty());

        HBox heading = new HBox(8, title);
        HBox.setHgrow(title, Priority.ALWAYS);
        heading.setAlignment(Pos.CENTER_LEFT);
        if (!flags.isEmpty()) {
            heading.getChildren().add(badge);
        }

        fieldRows.getStyleClass().add("card-fields");
        previewFields.forEach(field -> {
            Label row = new Label(field);
            row.getStyleClass().add("card-field");
            fieldRows.getChildren().add(row);
        });

        VBox body = new VBox(3, heading, subtitleLabel, fieldRows);
        body.setPadding(new Insets(9, 12, 9, inputFields.isEmpty() ? 14 : 88));

        BorderPane inner = new BorderPane();
        inner.setLeft(strip);
        inner.setCenter(body);
        getChildren().add(inner);

        // Input ports on the left edge, one per reference field (capped), with tiny labels.
        int shown = Math.min(inputFields.size(), MAX_INPUT_PORTS);
        for (int i = 0; i < shown; i++) {
            String fieldKey = inputFields.get(i);
            double y = (i + 1) * HEIGHT / (shown + 1.0);
            Circle circle = new Circle(PORT_RADIUS);
            circle.getStyleClass().addAll("port", "port-in");
            place(circle, 8, y);
            Label label = new Label(ellipsize(fieldKey, 11));
            label.getStyleClass().add("port-label");
            place(label, 16, y - 8);
            ports.add(new Port(null, fieldKey, 8 + PORT_RADIUS, y, circle));
        }

        // ID output port on the right edge.
        Circle id = new Circle(PORT_RADIUS);
        id.getStyleClass().addAll("port", "port-out");
        place(id, WIDTH - 8, HEIGHT / 2.0);
        ports.add(new Port(null, null, WIDTH - 8 + PORT_RADIUS, HEIGHT / 2.0, id));
    }

    /** Called by the canvas so each port knows which node it belongs to. */
    void bindNodeId(String nodeId) {
        ports.replaceAll(port -> new Port(nodeId, port.fieldKey, port.x, port.y, port.circle));
    }

    List<Port> ports() {
        return ports;
    }

    /** Card-relative anchor of a port, or null when the card has no such port. */
    Point2D portAnchor(String fieldKey) {
        for (Port port : ports) {
            if (Objects.equals(port.fieldKey, fieldKey)) {
                return new Point2D(port.x, port.y);
            }
        }
        return null;
    }

    private void place(javafx.scene.Node node, double x, double y) {
        node.setManaged(false);
        node.setLayoutX(x);
        node.setLayoutY(y);
        getChildren().add(node);
    }

    private static String ellipsize(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
