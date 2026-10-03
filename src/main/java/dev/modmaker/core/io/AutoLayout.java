package dev.modmaker.core.io;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ModProject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic layout for imported content: one band of columns per content type, in the order the
 * types are declared, wrapping into extra columns when a type has many entries. Being deterministic
 * keeps imports reproducible.
 */
public final class AutoLayout {

    public static final double CARD_WIDTH = 260;
    public static final double CARD_HEIGHT = 130;
    private static final double GAP_X = 60;
    private static final double GAP_Y = 28;
    private static final double MARGIN = 60;
    private static final int ROWS_PER_COLUMN = 18;

    private AutoLayout() {
    }

    /** Layout origin, shared with code that places single new cards. */
    public static double margin() {
        return MARGIN;
    }

    /** Vertical gap between cards, shared with code that places single new cards. */
    public static double gap() {
        return GAP_Y;
    }

    /** Places one node per content record; returns record id -> node id. */
    public static Map<String, String> apply(ModProject project, Board board) {
        Map<String, String> nodesByContent = new LinkedHashMap<>();
        Map<ContentType, Integer> counts = new LinkedHashMap<>();
        for (var record : project.contents) {
            counts.merge(record.type, 1, Integer::sum);
        }

        Map<ContentType, Double> columnStart = new LinkedHashMap<>();
        double x = MARGIN;
        for (ContentType type : ContentType.values()) {
            int count = counts.getOrDefault(type, 0);
            if (count == 0) {
                continue;
            }
            columnStart.put(type, x);
            int subColumns = (count + ROWS_PER_COLUMN - 1) / ROWS_PER_COLUMN;
            x += subColumns * (CARD_WIDTH + GAP_X);
        }

        Map<ContentType, Integer> placed = new LinkedHashMap<>();
        for (var record : project.contents) {
            int index = placed.merge(record.type, 1, Integer::sum) - 1;
            int subColumn = index / ROWS_PER_COLUMN;
            int row = index % ROWS_PER_COLUMN;

            CanvasNode node = new CanvasNode();
            node.id = dev.modmaker.core.fmt.Ids.uid("n");
            node.kind = CanvasNode.KIND_CONTENT;
            node.contentId = record.id;
            node.x = columnStart.get(record.type) + subColumn * (CARD_WIDTH + GAP_X);
            node.y = MARGIN + row * (CARD_HEIGHT + GAP_Y);
            board.nodes.add(node);
            nodesByContent.put(record.id, node.id);
        }
        return nodesByContent;
    }

    /** Re-places the nodes of a board, used by the "tidy layout" action. */
    public static void tidy(ModProject project, Board board) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < project.contents.size(); i++) {
            order.put(project.contents.get(i).id, i);
        }
        List<CanvasNode> contentNodes = board.nodes.stream()
            .filter(node -> CanvasNode.KIND_CONTENT.equals(node.kind))
            .sorted((a, b) -> Integer.compare(
                order.getOrDefault(a.contentId, Integer.MAX_VALUE),
                order.getOrDefault(b.contentId, Integer.MAX_VALUE)))
            .toList();

        Map<ContentType, Integer> placed = new LinkedHashMap<>();
        Map<String, double[]> positions = new LinkedHashMap<>();
        Map<ContentType, Double> columnStart = columnStarts(project);
        for (CanvasNode node : contentNodes) {
            var record = project.contentById(node.contentId);
            if (record == null) {
                continue;
            }
            int index = placed.merge(record.type, 1, Integer::sum) - 1;
            int subColumn = index / ROWS_PER_COLUMN;
            int row = index % ROWS_PER_COLUMN;
            positions.put(node.id, new double[] {
                columnStart.getOrDefault(record.type, MARGIN) + subColumn * (CARD_WIDTH + GAP_X),
                MARGIN + row * (CARD_HEIGHT + GAP_Y)});
        }
        for (CanvasNode node : board.nodes) {
            double[] position = positions.get(node.id);
            if (position != null) {
                node.x = position[0];
                node.y = position[1];
            }
        }
    }

    private static Map<ContentType, Double> columnStarts(ModProject project) {
        Map<ContentType, Integer> counts = new LinkedHashMap<>();
        for (var record : project.contents) {
            counts.merge(record.type, 1, Integer::sum);
        }
        Map<ContentType, Double> starts = new LinkedHashMap<>();
        double x = MARGIN;
        for (ContentType type : ContentType.values()) {
            int count = counts.getOrDefault(type, 0);
            if (count == 0) {
                continue;
            }
            starts.put(type, x);
            x += ((count + ROWS_PER_COLUMN - 1) / ROWS_PER_COLUMN) * (CARD_WIDTH + GAP_X);
        }
        return starts;
    }
}
