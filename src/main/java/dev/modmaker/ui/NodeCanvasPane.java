package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The node canvas: pan, zoom, drag, rubber-band selection, reference edges - and drawing new
 * connections by dragging between ports.
 *
 * <p>Coordinate handling: mouse events that bubble up from a card carry coordinates relative to the
 * card's inner node, so handlers always convert {@code event.getSceneX/Y()} to this pane's local
 * space first, then to world space. The interaction core is exposed as {@link #pressAt},
 * {@link #dragTo} and {@link #releaseAt} taking pane-local coordinates, which keeps the whole
 * interaction logic testable without OS-level input.
 */
public final class NodeCanvasPane extends Region {

    public static final DataFormat CONTENT_TYPE_FORMAT = new DataFormat("application/x-mm-ctype");

    private static final double MIN_ZOOM = 0.15;
    private static final double MAX_ZOOM = 2.5;
    private static final double PORT_HIT_RADIUS = 11;

    private final ProjectController controller;
    private final Group world = new Group();
    private final Pane edgeLayer = new Pane();
    private final Pane nodeLayer = new Pane();
    private final Rectangle rubber = new Rectangle();
    private final CubicCurve pendingCurve = new CubicCurve();
    private final Translate pan = new Translate();
    private final Scale zoom = new Scale(1, 1);

    private final Map<String, ContentCard> cardsByNode = new LinkedHashMap<>();
    private final Map<String, CubicCurve> curvesByEdge = new LinkedHashMap<>();
    private final Map<String, Label> labelsByEdge = new LinkedHashMap<>();
    private final Set<String> selectedNodes = new LinkedHashSet<>();
    private String selectedEdgeId;

    private Point2D pressLocal = Point2D.ZERO;
    private Point2D pressWorld = Point2D.ZERO;
    private boolean panning;
    private boolean rubberBanding;
    private String dragNode;
    private final Map<String, Point2D> dragOrigins = new LinkedHashMap<>();
    private ContentCard.Port pendingPort;
    private double cascade;

    public NodeCanvasPane(ProjectController controller) {
        this.controller = controller;
        getStyleClass().add("canvas-area");
        setFocusTraversable(true);

        edgeLayer.setManaged(false);
        nodeLayer.setManaged(false);
        world.getChildren().addAll(edgeLayer, nodeLayer);
        world.getTransforms().addAll(pan, zoom);
        getChildren().add(world);

        // JavaFX regions do not clip children by default: without this, a card dragged outside the
        // canvas (negative world coordinates, say) renders on top of the toolbar and side panels.
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        pendingCurve.getStyleClass().add("edge-pending");
        pendingCurve.setVisible(false);
        pendingCurve.setMouseTransparent(true);
        edgeLayer.getChildren().add(pendingCurve);

        rubber.getStyleClass().add("rubber-band");
        rubber.setVisible(false);
        getChildren().add(rubber);

        controller.onProjectChanged(this::refresh);
        controller.onSelectionChanged(this::highlightRecord);

        addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        addEventHandler(MouseEvent.MOUSE_RELEASED, this::onReleased);
        addEventHandler(ScrollEvent.SCROLL, this::onScroll);
        addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.DELETE) {
                return;
            }
            if (selectedEdgeId != null) {
                CanvasEdge edge = edgeById(selectedEdgeId);
                selectedEdgeId = null;
                if (edge != null) {
                    controller.disconnect(edge);
                }
            } else if (!selectedNodes.isEmpty()) {
                ContentRecord record = recordOf(selectedNodes.iterator().next());
                if (record != null) {
                    controller.deleteContent(record);
                }
            }
        });

        setOnDragOver(this::onDragOver);
        setOnDragDropped(this::onDragDropped);
        refresh();
    }

    // --- content --------------------------------------------------------------------------------

    public void refresh() {
        Board board = controller.board();
        cardsByNode.clear();
        curvesByEdge.clear();
        labelsByEdge.clear();
        pickCurves.clear();
        edgeLayer.getChildren().clear();
        edgeLayer.getChildren().add(pendingCurve);
        nodeLayer.getChildren().clear();
        selectedNodes.clear();
        selectedEdgeId = null;
        pendingPort = null;

        if (board == null || controller.project() == null) {
            return;
        }

        for (CanvasNode node : board.nodes) {
            ContentRecord record = controller.project().contentById(node.contentId);
            if (record == null) {
                continue;
            }
            List<String> labelledPreview = new ArrayList<>();
            for (String line : preview(record)) {
                int colon = line.indexOf(": ");
                labelledPreview.add(colon > 0
                    ? Labels.field(line.substring(0, colon)) + line.substring(colon)
                    : line);
            }
            ContentCard card = new ContentCard(record.type, controller.displayName(record),
                controller.subtitleOf(record), record.isInline(), record.patch, labelledPreview,
                inputPorts(record));
            card.bindNodeId(node.id);
            card.setLayoutX(node.x);
            card.setLayoutY(node.y);
            cardsByNode.put(node.id, card);
            nodeLayer.getChildren().add(card);
        }

        for (CanvasEdge edge : board.edges) {
            CubicCurve curve = new CubicCurve();
            curve.getStyleClass().add("edge");
            curvesByEdge.put(edge.id, curve);
            edgeLayer.getChildren().add(curve);

            CubicCurve pick = new CubicCurve();
            pick.getStyleClass().addAll("edge", "edge-pick");
            pick.setMouseTransparent(false);
            pick.setOnMouseClicked(event -> {
                selectedEdgeId = edge.id;
                highlightEdges();
                event.consume();
            });
            pickCurves.put(edge.id, pick);
            edgeLayer.getChildren().add(pick);

            if (edge.label != null && !edge.label.isEmpty()) {
                Label label = new Label(edge.label);
                label.getStyleClass().add("edge-label");
                label.setMouseTransparent(true);
                labelsByEdge.put(edge.id, label);
                edgeLayer.getChildren().add(label);
            }
        }
        refreshEdges();
        highlightEdges();

        pan.setX(board.viewport.x);
        pan.setY(board.viewport.y);
        zoom.setX(board.viewport.zoom);
        zoom.setY(board.viewport.zoom);
        world.setVisible(true);
        highlightRecord(controller.selected());
    }

    /** Reference fields of the record's class, shown as input ports (capped). */
    private List<String> inputPorts(ContentRecord record) {
        List<String> ports = new ArrayList<>();
        String className = controller.classNameOf(record);
        for (FieldDef field : controller.registry().fieldsOf(className)) {
            if (!field.isReference()) {
                continue;
            }
            ports.add(field.name());
            if (ports.size() >= ContentCard.MAX_INPUT_PORTS) {
                break;
            }
        }
        return ports;
    }

    /** Recomputes every edge curve from the current card positions and port anchors. */
    public void refreshEdges() {
        Board board = controller.board();
        if (board == null) {
            return;
        }
        for (CanvasEdge edge : board.edges) {
            Point2D start = portWorld(edge.from, null);
            Point2D end = portWorld(edge.to, edge.fieldKey);
            CubicCurve curve = curvesByEdge.get(edge.id);
            if (curve == null) {
                continue;
            }
            setCurve(curve, start, end);
            CubicCurve pickCurve = pickCurves.get(edge.id);
            if (pickCurve != null) {
                setCurve(pickCurve, start, end);
            }

            Label label = labelsByEdge.get(edge.id);
            if (label != null) {
                double midX = (start.getX() + end.getX()) / 2;
                double midY = (start.getY() + end.getY()) / 2;
                label.setLayoutX(midX - 20);
                label.setLayoutY(midY - 9);
            }
        }
    }

    private final Map<String, CubicCurve> pickCurves = new LinkedHashMap<>();

    private static void setCurve(CubicCurve curve, Point2D start, Point2D end) {
        double midX = start.getX() + (end.getX() - start.getX()) * 0.5;
        curve.setStartX(start.getX());
        curve.setStartY(start.getY());
        curve.setControlX1(midX);
        curve.setControlY1(start.getY());
        curve.setControlX2(midX);
        curve.setControlY2(end.getY());
        curve.setEndX(end.getX());
        curve.setEndY(end.getY());
    }

    /** Highlights the card of a record. */
    public void highlightRecord(ContentRecord record) {
        selectedNodes.clear();
        cardsByNode.values().forEach(card -> card.getStyleClass().remove("selected"));
        if (record == null || controller.board() == null) {
            return;
        }
        controller.board().nodes.stream()
            .filter(node -> record.id.equals(node.contentId))
            .findFirst()
            .ifPresent(node -> {
                selectedNodes.add(node.id);
                ContentCard card = cardsByNode.get(node.id);
                if (card != null) {
                    card.getStyleClass().add("selected");
                }
            });
    }

    private void highlightEdges() {
        curvesByEdge.forEach((edgeId, curve) -> {
            curve.getStyleClass().remove("edge-selected");
            if (edgeId.equals(selectedEdgeId)) {
                curve.getStyleClass().add("edge-selected");
            }
        });
    }

    /** Moves the view so every card is visible, in one step. */
    public void fitToContent() {
        if (cardsByNode.isEmpty() || getWidth() <= 0) {
            return;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (ContentCard card : cardsByNode.values()) {
            minX = Math.min(minX, card.getLayoutX());
            minY = Math.min(minY, card.getLayoutY());
            maxX = Math.max(maxX, card.getLayoutX() + ContentCard.WIDTH);
            maxY = Math.max(maxY, card.getLayoutY() + ContentCard.HEIGHT);
        }
        double contentWidth = Math.max(1, maxX - minX);
        double contentHeight = Math.max(1, maxY - minY);
        double scale = Math.min(getWidth() / (contentWidth + 120), getHeight() / (contentHeight + 120));
        scale = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, scale));
        zoom.setX(scale);
        zoom.setY(scale);
        pan.setX((getWidth() - contentWidth * scale) / 2 - minX * scale);
        pan.setY((getHeight() - contentHeight * scale) / 2 - minY * scale);
        rememberViewport();
    }

    /** World coordinates of the pane centre, for placing new cards where the user can see them. */
    public Point2D viewportCenterWorld() {
        double scale = zoom.getX() == 0 ? 1 : zoom.getX();
        return new Point2D((getWidth() / 2 - pan.getX()) / scale,
            (getHeight() / 2 - pan.getY()) / scale);
    }

    /** Growing offset so several "Add" clicks do not stack cards exactly on top of each other. */
    public double nextCascade() {
        cascade = (cascade + 44) % 220;
        return cascade;
    }

    // --- input layer (coordinates only) ---------------------------------------------------------

    private void onPressed(MouseEvent event) {
        // Clicking an edge's pick curve must not clear the selection or start a rubber band.
        if (event.getTarget() instanceof CubicCurve curve && curve.getStyleClass().contains("edge-pick")) {
            return;
        }
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        pressAt(local.getX(), local.getY(), event.getButton());
    }

    private void onDragged(MouseEvent event) {
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        dragTo(local.getX(), local.getY());
    }

    private void onReleased(MouseEvent event) {
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        releaseAt(local.getX(), local.getY());
    }

    private void onScroll(ScrollEvent event) {
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        if (event.isControlDown()) {
            double factor = event.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            double current = zoom.getX();
            double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, current * factor));
            if (next == current) {
                return;
            }
            Point2D worldPoint = toWorld(local.getX(), local.getY());
            zoom.setX(next);
            zoom.setY(next);
            pan.setX(local.getX() - worldPoint.getX() * next);
            pan.setY(local.getY() - worldPoint.getY() * next);
        } else {
            pan.setX(pan.getX() + event.getDeltaX());
            pan.setY(pan.getY() + event.getDeltaY());
        }
        rememberViewport();
        event.consume();
    }

    // --- interaction core (pane-local coordinates; unit-testable) -------------------------------

    /** Mouse press at pane-local coordinates. */
    public void pressAt(double localX, double localY, MouseButton button) {
        requestFocus();
        pressLocal = new Point2D(localX, localY);
        pressWorld = toWorld(localX, localY);

        if (button == MouseButton.MIDDLE || button == MouseButton.SECONDARY) {
            panning = true;
            return;
        }

        ContentCard.Port port = portAt(pressWorld.getX(), pressWorld.getY());
        if (port != null) {
            pendingPort = port;
            Point2D anchor = portWorld(port.nodeId, port.fieldKey);
            pendingCurve.setVisible(true);
            setCurve(pendingCurve, anchor, anchor);
            return;
        }

        String nodeId = nodeAt(pressWorld.getX(), pressWorld.getY());
        if (nodeId == null) {
            selectedEdgeId = null;
            highlightEdges();
            selectedNodes.clear();
            cardsByNode.values().forEach(card -> card.getStyleClass().remove("selected"));
            rubberBanding = true;
            rubber.setX(localX);
            rubber.setY(localY);
            rubber.setWidth(0);
            rubber.setHeight(0);
            rubber.setVisible(true);
            return;
        }

        dragNode = nodeId;
        dragOrigins.clear();
        if (!selectedNodes.contains(nodeId)) {
            selectedNodes.clear();
            cardsByNode.values().forEach(card -> card.getStyleClass().remove("selected"));
            selectedNodes.add(nodeId);
            cardsByNode.get(nodeId).getStyleClass().add("selected");
        }
        for (String selected : selectedNodes) {
            ContentCard card = cardsByNode.get(selected);
            if (card != null) {
                dragOrigins.put(selected, new Point2D(card.getLayoutX(), card.getLayoutY()));
            }
        }
        ContentRecord record = recordOf(nodeId);
        if (record != null) {
            controller.select(record);
        }
    }

    /** Mouse drag to pane-local coordinates. */
    public void dragTo(double localX, double localY) {
        Point2D world = toWorld(localX, localY);
        if (panning) {
            pan.setX(pan.getX() + (localX - pressLocal.getX()));
            pan.setY(pan.getY() + (localY - pressLocal.getY()));
            pressLocal = new Point2D(localX, localY);
            rememberViewport();
            return;
        }
        if (pendingPort != null) {
            setCurve(pendingCurve, portWorld(pendingPort.nodeId, pendingPort.fieldKey), world);
            return;
        }
        if (rubberBanding) {
            rubber.setX(Math.min(pressLocal.getX(), localX));
            rubber.setY(Math.min(pressLocal.getY(), localY));
            rubber.setWidth(Math.abs(localX - pressLocal.getX()));
            rubber.setHeight(Math.abs(localY - pressLocal.getY()));
            return;
        }
        if (dragNode != null) {
            double deltaX = world.getX() - pressWorld.getX();
            double deltaY = world.getY() - pressWorld.getY();
            dragOrigins.forEach((nodeId, origin) -> {
                ContentCard card = cardsByNode.get(nodeId);
                CanvasNode node = nodeById(nodeId);
                if (card == null || node == null) {
                    return;
                }
                node.x = origin.getX() + deltaX;
                node.y = origin.getY() + deltaY;
                card.setLayoutX(node.x);
                card.setLayoutY(node.y);
            });
            refreshEdges();
        }
    }

    /** Mouse release at pane-local coordinates. */
    public void releaseAt(double localX, double localY) {
        Point2D world = toWorld(localX, localY);

        if (pendingPort != null) {
            ContentCard.Port target = portAt(world.getX(), world.getY());
            if (target != null && target != pendingPort) {
                ContentCard.Port out = pendingPort.fieldKey == null ? pendingPort : target;
                ContentCard.Port in = pendingPort.fieldKey == null ? target : pendingPort;
                if (out.fieldKey == null && in.fieldKey != null
                    && !out.nodeId.equals(in.nodeId)) {
                    controller.connect(out.nodeId, in.nodeId, in.fieldKey);
                } else {
                    controller.log("a connection needs one ID port and one reference port");
                }
            }
            pendingPort = null;
            pendingCurve.setVisible(false);
            return;
        }

        if (rubberBanding) {
            rubberBanding = false;
            rubber.setVisible(false);
            selectInRubber();
        }
        if (dragNode != null && !dragOrigins.isEmpty()) {
            commitMove();
        }
        panning = false;
        dragNode = null;
        dragOrigins.clear();
    }

    private void commitMove() {
        Map<String, double[]> after = new LinkedHashMap<>();
        for (String nodeId : dragOrigins.keySet()) {
            CanvasNode node = nodeById(nodeId);
            if (node != null) {
                after.put(nodeId, new double[] {node.x, node.y});
            }
        }
        controller.commitMoves(dragOrigins, after);
    }

    private void selectInRubber() {
        if (rubber.getWidth() < 3 && rubber.getHeight() < 3) {
            return;
        }
        Point2D topLeft = toWorld(rubber.getX(), rubber.getY());
        Point2D bottomRight = toWorld(rubber.getX() + rubber.getWidth(),
            rubber.getY() + rubber.getHeight());
        for (Map.Entry<String, ContentCard> entry : cardsByNode.entrySet()) {
            ContentCard card = entry.getValue();
            boolean inside = card.getLayoutX() + ContentCard.WIDTH >= topLeft.getX()
                && card.getLayoutX() <= bottomRight.getX()
                && card.getLayoutY() + ContentCard.HEIGHT >= topLeft.getY()
                && card.getLayoutY() <= bottomRight.getY();
            if (inside) {
                selectedNodes.add(entry.getKey());
                card.getStyleClass().add("selected");
            }
        }
        if (selectedNodes.size() == 1) {
            ContentRecord record = recordOf(selectedNodes.iterator().next());
            if (record != null) {
                controller.select(record);
            }
        }
    }

    // --- palette drag & drop --------------------------------------------------------------------

    private void onDragOver(DragEvent event) {
        if (event.getDragboard().hasContent(CONTENT_TYPE_FORMAT) && controller.project() != null) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void onDragDropped(DragEvent event) {
        Dragboard board = event.getDragboard();
        if (board.hasContent(CONTENT_TYPE_FORMAT) && controller.project() != null) {
            ContentType type = ContentType.valueOf(String.valueOf(board.getContent(CONTENT_TYPE_FORMAT)));
            Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
            Point2D world = toWorld(local.getX(), local.getY());
            controller.addContent(type, world.getX() - ContentCard.WIDTH / 2,
                world.getY() - ContentCard.HEIGHT / 2);
            event.setDropCompleted(true);
        }
        event.consume();
    }

    // --- geometry helpers -----------------------------------------------------------------------

    /** Converts pane-local coordinates to world (canvas) coordinates. */
    private Point2D toWorld(double localX, double localY) {
        double scale = zoom.getX() == 0 ? 1 : zoom.getX();
        return new Point2D((localX - pan.getX()) / scale, (localY - pan.getY()) / scale);
    }

    /** World position of a node's port. {@code fieldKey == null} is the ID port. */
    public Point2D portWorld(String nodeId, String fieldKey) {
        ContentCard card = cardsByNode.get(nodeId);
        if (card == null) {
            return Point2D.ZERO;
        }
        Point2D anchor = card.portAnchor(fieldKey);
        if (anchor == null) {
            anchor = fieldKey == null
                ? new Point2D(ContentCard.WIDTH, ContentCard.HEIGHT / 2)
                : new Point2D(0, ContentCard.HEIGHT / 2);
        }
        return new Point2D(card.getLayoutX() + anchor.getX(), card.getLayoutY() + anchor.getY());
    }

    private ContentCard.Port portAt(double worldX, double worldY) {
        ContentCard.Port best = null;
        double bestDistance = PORT_HIT_RADIUS * PORT_HIT_RADIUS;
        for (ContentCard card : cardsByNode.values()) {
            for (ContentCard.Port port : card.ports()) {
                double dx = card.getLayoutX() + port.x - worldX;
                double dy = card.getLayoutY() + port.y - worldY;
                double distance = dx * dx + dy * dy;
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = port;
                }
            }
        }
        return best;
    }

    private String nodeAt(double worldX, double worldY) {
        for (Map.Entry<String, ContentCard> entry : cardsByNode.entrySet()) {
            ContentCard card = entry.getValue();
            if (worldX >= card.getLayoutX()
                && worldX <= card.getLayoutX() + ContentCard.WIDTH
                && worldY >= card.getLayoutY()
                && worldY <= card.getLayoutY() + ContentCard.HEIGHT) {
                return entry.getKey();
            }
        }
        return null;
    }

    private CanvasNode nodeById(String nodeId) {
        if (controller.board() == null) {
            return null;
        }
        return controller.board().nodes.stream()
            .filter(node -> node.id.equals(nodeId))
            .findFirst().orElse(null);
    }

    private CanvasEdge edgeById(String edgeId) {
        if (controller.board() == null) {
            return null;
        }
        return controller.board().edges.stream()
            .filter(edge -> edge.id.equals(edgeId))
            .findFirst().orElse(null);
    }

    private ContentRecord recordOf(String nodeId) {
        CanvasNode node = nodeById(nodeId);
        return node == null ? null : controller.project().contentById(node.contentId);
    }

    private void rememberViewport() {
        Board board = controller.board();
        if (board != null) {
            board.viewport.x = pan.getX();
            board.viewport.y = pan.getY();
            board.viewport.zoom = zoom.getX();
        }
    }

    /** A few field values to show on the card, so a card is informative without opening it. */
    private List<String> preview(ContentRecord record) {
        List<String> lines = new ArrayList<>();
        String className = controller.classNameOf(record);
        for (FieldDef field : controller.registry().fieldsOf(className)) {
            if (lines.size() >= 3) {
                break;
            }
            var value = record.fields.get(field.name());
            if (value == null || value.isJsonNull()) {
                continue;
            }
            String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
            if (text.length() > 42) {
                text = text.substring(0, 39) + "...";
            }
            lines.add(field.name() + ": " + text);
        }
        return lines;
    }
}
