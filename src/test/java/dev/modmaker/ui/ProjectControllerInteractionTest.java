package dev.modmaker.ui;

import com.google.gson.JsonArray;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller-level interaction contract: placing, connecting, disconnecting, moving and deleting
 * - each with working undo and redo. Pure JVM, no JavaFX toolkit needed.
 */
class ProjectControllerInteractionTest {

    private static SchemaRegistry registry;
    private ProjectController controller;
    private Path root;

    @BeforeAll
    static void loadRegistry() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    private ModProject newProject() throws IOException {
        root = Files.createTempDirectory("mm-interact");
        controller = new ProjectController(registry);
        return controller.createProject("test-mod", root);
    }

    private CanvasNode nodeOf(ContentRecord record) {
        return controller.board().nodes.stream()
            .filter(node -> record.id.equals(node.contentId))
            .findFirst().orElseThrow();
    }

    @Test
    void addContentPlacesNodeAtGivenPosition() throws IOException {
        newProject();
        ContentRecord record = controller.addContent(ContentType.item, 120d, 90d);
        CanvasNode node = nodeOf(record);
        assertEquals(120, node.x, 0.001);
        assertEquals(90, node.y, 0.001);
    }

    @Test
    void connectWritesTheFieldAndAddsTheEdge() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        String itemNodeId = nodeOf(item).id;
        String blockNodeId = nodeOf(block).id;

        assertTrue(controller.connect(itemNodeId, blockNodeId, "requirements"));
        JsonArray requirements = block.fields.get("requirements").getAsJsonArray();
        assertEquals(1, requirements.size());
        assertEquals("new-item-1", requirements.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(1, controller.board().edges.size());
        assertTrue(block.dirty, "the target record must be re-serialised on save");

        assertFalse(controller.connect(itemNodeId, blockNodeId, "requirements"), "duplicate is rejected");
    }

    @Test
    void disconnectRemovesFieldAndEdge() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        controller.connect(nodeOf(item).id, nodeOf(block).id, "requirements");
        CanvasEdge edge = controller.board().edges.get(0);

        controller.disconnect(edge);
        assertFalse(block.fields.containsKey("requirements"));
        assertTrue(controller.board().edges.isEmpty());

        controller.undo();
        assertTrue(block.fields.containsKey("requirements"), "undo restores the reference");
        assertEquals(1, controller.board().edges.size());
        controller.redo();
        assertFalse(block.fields.containsKey("requirements"));
    }

    @Test
    void undoRedoCoversAddMoveAndDelete() throws IOException {
        newProject();
        int recordsBefore = controller.project().contents.size();

        ContentRecord record = controller.addContent(ContentType.item, 10d, 20d);
        CanvasNode node = nodeOf(record);
        assertEquals(recordsBefore + 1, controller.project().contents.size());

        Map<String, javafx.geometry.Point2D> before = new LinkedHashMap<>();
        before.put(node.id, new javafx.geometry.Point2D(10, 20));
        node.x = 300;
        node.y = 400;
        Map<String, double[]> after = new LinkedHashMap<>();
        after.put(node.id, new double[] {300, 400});
        controller.commitMoves(before, after);
        assertEquals(300, node.x, 0.001);

        controller.undo(); // move back
        assertEquals(10, node.x, 0.001);
        assertEquals(20, node.y, 0.001);

        controller.deleteContent(record);
        assertFalse(controller.project().contents.contains(record));
        assertTrue(controller.board().nodes.stream().noneMatch(n -> n.id.equals(node.id)));

        controller.undo(); // undelete
        assertTrue(controller.project().contents.contains(record));
        assertTrue(controller.board().nodes.stream().anyMatch(n -> n.id.equals(node.id)));

        controller.redo(); // delete again
        assertFalse(controller.project().contents.contains(record));

        controller.undo(); // undelete
        controller.undo(); // un-move (already undone above; this un-adds)
        assertEquals(recordsBefore, controller.project().contents.size());
    }

    @Test
    void historySurvivesSaveAndConnectWritesReachDisk() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        controller.connect(nodeOf(item).id, nodeOf(block).id, "requirements");
        ProjectIo.save(controller.project(), root);

        controller.undo();
        assertFalse(controller.project().contents.stream()
            .filter(record -> record.type == ContentType.block)
            .findFirst().orElseThrow().fields.containsKey("requirements"));
        ProjectIo.save(controller.project(), root);

        String blockJson = Files.readString(
            root.resolve("content/blocks/new-block-1.json"));
        assertFalse(blockJson.contains("new-item-1"), "the undone reference is gone from disk");
    }
}
