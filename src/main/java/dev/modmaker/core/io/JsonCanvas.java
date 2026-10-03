package dev.modmaker.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.json.JsonPath;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ModProject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON Canvas 1.0 read/write for boards, so the authoring layer stays openable in Obsidian.
 *
 * <p>Node and edge semantics the canvas cannot express live in an extension member named
 * "mindustry": which content record a node shows, and which field an edge stands for. Obsidian shows
 * the node text and ignores the rest.
 */
public final class JsonCanvas {

    private JsonCanvas() {
    }

    public static JsonObject toCanvas(ModProject project, Board board) {
        JsonObject canvas = new JsonObject();
        JsonArray nodes = new JsonArray();
        for (CanvasNode node : board.nodes) {
            JsonObject json = new JsonObject();
            json.addProperty("id", node.id);
            json.addProperty("type", CanvasNode.KIND_GROUP.equals(node.kind) ? "group" : "text");
            json.addProperty("x", node.x);
            json.addProperty("y", node.y);
            json.addProperty("width", node.w);
            json.addProperty("height", node.h);
            json.addProperty("text", nodeText(project, node));
            if (CanvasNode.KIND_CONTENT.equals(node.kind)) {
                var record = project.contentById(node.contentId);
                json.addProperty("color", record == null ? "#3f3f46" : record.type.color());
            } else if (node.color != null && !node.color.isEmpty()) {
                json.addProperty("color", node.color);
            }

            JsonObject extension = new JsonObject();
            extension.addProperty("kind", node.kind);
            if (node.contentId != null) {
                extension.addProperty("contentId", node.contentId);
            }
            json.add("mindustry", extension);
            nodes.add(json);
        }

        JsonArray edges = new JsonArray();
        for (CanvasEdge edge : board.edges) {
            JsonObject json = new JsonObject();
            json.addProperty("id", edge.id);
            json.addProperty("fromNode", edge.from);
            json.addProperty("toNode", edge.to);
            json.addProperty("fromSide", "right");
            json.addProperty("toSide", "left");
            if (edge.label != null && !edge.label.isEmpty()) {
                json.addProperty("label", edge.label);
            }
            JsonObject extension = new JsonObject();
            if (edge.fieldKey != null) {
                extension.addProperty("fieldKey", edge.fieldKey);
            }
            json.add("mindustry", extension);
            edges.add(json);
        }

        canvas.add("nodes", nodes);
        canvas.add("edges", edges);
        return canvas;
    }

    public static Board fromCanvas(String text, String boardFile, String boardId) {
        Board board = new Board();
        board.id = boardId;
        board.file = boardFile;
        board.name = boardFile.endsWith(".canvas")
            ? boardFile.substring(0, boardFile.length() - ".canvas".length())
            : boardFile;

        JsonObject canvas = Json.parseObject(text);
        Map<String, CanvasNode> nodesById = new LinkedHashMap<>();
        JsonArray nodes = canvas.getAsJsonArray("nodes");
        if (nodes != null) {
            for (JsonElement element : nodes) {
                JsonObject json = element.getAsJsonObject();
                CanvasNode node = new CanvasNode();
                node.id = string(json, "id", dev.modmaker.core.fmt.Ids.uid("n"));
                node.x = number(json, "x", 0);
                node.y = number(json, "y", 0);
                node.w = number(json, "width", CanvasNode.KIND_GROUP.equals(type(json)) ? 400 : 260);
                node.h = number(json, "height", CanvasNode.KIND_GROUP.equals(type(json)) ? 300 : 130);
                node.text = string(json, "text", "");
                node.color = string(json, "color", "");
                node.kind = type(json);
                JsonObject extension = json.getAsJsonObject("mindustry");
                if (extension != null && extension.has("contentId")) {
                    node.contentId = extension.get("contentId").getAsString();
                }
                if (CanvasNode.KIND_CONTENT.equals(node.kind) && node.contentId == null) {
                    // A plain Obsidian card carries no content; keep it as a note so nothing is lost.
                    node.kind = CanvasNode.KIND_NOTE;
                }
                board.nodes.add(node);
                nodesById.put(node.id, node);
            }
        }

        JsonArray edges = canvas.getAsJsonArray("edges");
        if (edges != null) {
            for (JsonElement element : edges) {
                JsonObject json = element.getAsJsonObject();
                CanvasEdge edge = new CanvasEdge();
                edge.id = string(json, "id", dev.modmaker.core.fmt.Ids.uid("e"));
                edge.from = string(json, "fromNode", "");
                edge.to = string(json, "toNode", "");
                edge.label = string(json, "label", "");
                JsonObject extension = json.getAsJsonObject("mindustry");
                if (extension != null && extension.has("fieldKey")) {
                    edge.fieldKey = extension.get("fieldKey").getAsString();
                }
                if (nodesById.containsKey(edge.from) && nodesById.containsKey(edge.to)) {
                    board.edges.add(edge);
                }
            }
        }
        return board;
    }

    private static String nodeText(ModProject project, CanvasNode node) {
        if (CanvasNode.KIND_CONTENT.equals(node.kind)) {
            var record = project.contentById(node.contentId);
            if (record == null) {
                return "**(missing content)**";
            }
            String className = record.className() == null ? record.type.defaultClass() : record.className();
            String display = project.strings.get(
                dev.modmaker.core.fmt.NameRules.bundleKey(record.type, record.fullName(project.internalName()), "name"),
                "en");
            if (display == null || display.isBlank()) {
                display = record.name;
            }
            return "**" + display + "**\n" + record.type.name() + " · " + className
                + (record.isInline() ? " · inline" : "") + (record.patch ? " · patch" : "");
        }
        return node.text == null ? "" : node.text;
    }

    private static String type(JsonObject json) {
        JsonObject extension = json.getAsJsonObject("mindustry");
        if (extension != null && extension.has("kind")) {
            return extension.get("kind").getAsString();
        }
        String type = string(json, "type", "text");
        return "group".equals(type) ? CanvasNode.KIND_GROUP : CanvasNode.KIND_NOTE;
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    private static double number(JsonObject json, String key, double fallback) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsDouble();
    }

    /** Exposed for tests: the path helper used when pointing at nested content. */
    static String nestedPath(String parent, String key) {
        return JsonPath.child(parent, key);
    }
}
