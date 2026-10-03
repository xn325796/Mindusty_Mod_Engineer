package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Workspace;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * UI smoke tests on the real JavaFX toolkit: build the shell, count content cards and input ports in
 * the scene graph, snapshot the window, and drive the canvas interaction core (card drag + port
 * connection + undo/redo) through the same entry points the mouse uses.
 *
 * <p>The toolkit is started once per test JVM and shut down via a hook, so tests can run in sequence.
 */
class UiSmokeTest {

    private static boolean fxStarted;

    /** True when this JVM's tests started the JavaFX toolkit (read by the session listener). */
    static boolean toolkitWasStarted() {
        return fxStarted;
    }

    private static synchronized void ensureFxStarted() {
        if (fxStarted) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        fxStarted = true;
        try {
            assumeTrue(ready.await(60, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (InterruptedException failed) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failed);
        }
    }

    private interface FxTask {
        void run() throws Exception;
    }

    private static void onFx(FxTask task) throws Exception {
        ensureFxStarted();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new AssertionError("FX task timed out");
        }
        Throwable thrown = failure.get();
        if (thrown != null) {
            if (thrown instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(thrown);
        }
    }

    @Test
    void shellRendersProjectCards() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-smoke");
            ModProject project = controller.createProject("ui-smoke", root);
            controller.addContent(ContentType.item, 60d, 60d);
            controller.addContent(ContentType.block, 460d, 60d);
            assertEquals(2, project.contents.size());

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            // Make the block a crafter so the snapshot shows class-adaptive fields in the inspector.
            controller.setField(project.contents.get(1), "type", new com.google.gson.JsonPrimitive("GenericCrafter"));
            controller.select(project.contents.get(1));
            // TabPane attaches the selected tab's content during layout; force it so traversal sees
            // the canvas cards without showing a window.
            shell.applyCss();
            shell.layout();

            int cards = countCards(shell);
            int inputPorts = countInputPorts(shell);

            Path png = Path.of("snapshots", "ui-smoke.png");
            Snapshotter.write(scene, png);
            long pngBytes = Files.size(png);

            assertEquals(2, cards, "both content cards must exist in the scene graph");
            assertTrue(inputPorts > 0, "the block card must show reference input ports");
            assertTrue(pngBytes > 5_000, "the snapshot should show something, saw " + pngBytes + " bytes");
            System.out.println("ui smoke: cards=" + cards + " inputPorts=" + inputPorts
                + " snapshot=" + pngBytes + " bytes");
        });
    }

    /**
     * The full interaction core on a real scene graph: drag a card, then drag from one card's ID
     * port to another card's input port and verify the reference was written - without OS-level
     * input, by driving the same {@code pressAt/dragTo/releaseAt} entry points the mouse uses.
     */
    @Test
    void canvasInteractionsDragCardsAndDrawConnections() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-interact");
            controller.createProject("interact", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            ContentRecord block = controller.addContent(ContentType.block, 500d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas); // attach to a scene so sceneToLocal is well-defined

            String itemId = boardNodeId(controller, item.id);
            String blockId = boardNodeId(controller, block.id);

            // 1. Drag the item card by its body and verify the node moved.
            canvas.pressAt(200, 150, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(260, 190);
            canvas.releaseAt(260, 190);
            var itemNode = controller.board().nodes.stream()
                .filter(node -> node.contentId.equals(item.id)).findFirst().orElseThrow();
            assertEquals(160, itemNode.x, 0.001, "card drag must move the node");
            assertEquals(140, itemNode.y, 0.001);

            // 2. Drag from the item's ID port to the block's requirements port.
            javafx.geometry.Point2D out = canvas.portWorld(itemId, null);
            javafx.geometry.Point2D in = canvas.portWorld(blockId, "requirements");
            canvas.pressAt(out.getX(), out.getY(), javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(in.getX(), in.getY());
            canvas.releaseAt(in.getX(), in.getY());

            var requirements = block.fields.get("requirements");
            assertTrue(requirements != null && requirements.isJsonArray()
                && requirements.getAsJsonArray().size() == 1,
                "requirements must be written by the connection, saw " + requirements);
            assertEquals(1, controller.board().edges.size(), "one edge after connecting");

            // 3. Undo removes the reference, redo restores it.
            controller.undo();
            assertFalse(block.fields.containsKey("requirements"), "undo removes the reference");
            controller.redo();
            assertTrue(block.fields.containsKey("requirements"), "redo restores the reference");
            System.out.println("interaction smoke: drag + connect + undo/redo all work");
        });
    }

    /**
     * Regression test for the reported bug: a card dragged outside the canvas (negative world
     * coordinates) must be clipped at the canvas bounds, not drawn on top of the toolbar and the
     * side panels. Verified at the pixel level on a rendered snapshot.
     */
    @Test
    void cardsOutsideTheCanvasAreClipped() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-clip");
            controller.createProject("clip", root);
            controller.addContent(ContentType.item, 0d, 0d);

            // Real-app layout: toolbar strip on top, palette strip on the left, canvas in the center.
            javafx.scene.layout.BorderPane rootPane = new javafx.scene.layout.BorderPane();
            javafx.scene.layout.Region topBar = new javafx.scene.layout.Region();
            topBar.setStyle("-fx-background-color: #ff00ff;");
            topBar.setPrefHeight(40);
            javafx.scene.layout.Region leftBar = new javafx.scene.layout.Region();
            leftBar.setStyle("-fx-background-color: #00ff00;");
            leftBar.setPrefWidth(100);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            rootPane.setTop(topBar);
            rootPane.setLeft(leftBar);
            rootPane.setCenter(canvas);
            Scene scene = new Scene(rootPane, 800, 600);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());

            // Drag the card so it hangs out over the top bar and the left panel.
            canvas.pressAt(130, 65, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(-100, -20);
            canvas.releaseAt(-100, -20);
            var node = controller.board().nodes.get(0);
            assertEquals(-230, node.x, 0.001, "drag must move the node");
            assertEquals(-85, node.y, 0.001);

            java.awt.image.BufferedImage image =
                Snapshotter.write(scene, root.resolve("clip-check.png"));

            // Points that lie over the bars and would show card pixels without clipping.
            assertMagenta(image, 200, 20, "top bar right of the card");
            assertGreen(image, 50, 300, "left panel below the card");
            // The card itself is still visible inside the canvas.
            int inside = image.getRGB(60, 80);
            assertTrue((inside >> 16 & 0xFF) < 100, "the card must still render inside the canvas");
            System.out.println("clip smoke: outside bars clean, card renders clipped inside canvas");
        });
    }

    private static void assertMagenta(java.awt.image.BufferedImage image, int x, int y, String what) {
        int rgb = image.getRGB(x, y);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        assertTrue(red > 200 && blue > 200 && green < 100,
            what + " must show the bar (magenta), saw rgb(" + red + "," + green + "," + blue + ")");
    }

    private static void assertGreen(java.awt.image.BufferedImage image, int x, int y, String what) {
        int rgb = image.getRGB(x, y);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        assertTrue(green > 200 && red < 100 && blue < 100,
            what + " must show the panel (green), saw rgb(" + red + "," + green + "," + blue + ")");
    }

    /**
     * UI-level regression for the adaptive class dropdown: with a battery selected, the offered
     * class choices must stay inside the power branch - no item bridges, no ducts.
     */
    @Test
    void batteryRecordHasNoTransportClassesInItsDropdown() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-adaptive");
            ModProject project = controller.createProject("adaptive", root);
            controller.addContent(ContentType.block, 0d, 0d);
            controller.setField(project.contents.get(0), "type",
                new com.google.gson.JsonPrimitive("Battery"));

            AppShell shell = new AppShell(controller);
            new Scene(shell, 1400, 900);
            controller.select(project.contents.get(0));

            List<String> choices = shell.inspectorChoices();
            assertTrue(choices.contains("Battery"), choices.toString());
            assertTrue(choices.contains("PowerNode"), choices.toString());
            assertTrue(choices.contains("Block"), "the base class must stay selectable: " + choices);
            for (String forbidden : new String[] {"Conduit", "Duct", "ItemBridge", "Wall",
                "GenericCrafter", "Drill", "Router"}) {
                assertFalse(choices.contains(forbidden),
                    forbidden + " must not be offered for a battery: " + choices);
            }
            System.out.println("adaptive smoke: battery choices=" + choices.size());
        });
    }

    /**
     * The sprite tab environment: lists the project's sprite assets with a working preview and the
     * content records still missing their sprite.
     */
    @Test
    void spritePaneListsAssetsAndMissing() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-sprites");
            ModProject project = controller.createProject("sprites", root);
            controller.addContent(ContentType.block, 0d, 0d);

            // A real 2x2 png so the preview can actually load it.
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(1, 1, 0xFFFF00FF);
            Path spriteFile = root.resolve("sprites/tiny.png");
            Files.createDirectories(spriteFile.getParent());
            javax.imageio.ImageIO.write(img, "png", spriteFile.toFile());
            project.assets.add(new dev.modmaker.core.model.AssetRef("sprites/tiny.png",
                Files.size(spriteFile), "sprite"));

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.selectTab("bottom.spritesTab");

            var pane = shell.spritePane();
            assertEquals(List.of("sprites/tiny.png"), pane.listedFiles());
            assertTrue(pane.canLoad("sprites/tiny.png"), "the png must be loadable from the project");
            assertEquals(1, pane.missingNames().size(),
                "the block has no sprite: " + pane.missingNames());

            Snapshotter.write(scene, Path.of("snapshots", "ui-sprites.png"));
            System.out.println("sprite smoke: files=" + pane.listedFiles()
                + " missing=" + pane.missingNames());
        });
    }

    /**
     * The planet preview renders the sector grid of the selected planet and marks the tiles its
     * sector presets occupy; capacity follows the game's 10*3^size + 2 formula.
     */
    @Test
    void planetPreviewRendersGridAndOccupancy() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-cosmos");
            ModProject project = controller.createProject("cosmos", root);
            ContentRecord planet = controller.addContent(ContentType.planet, 0d, 0d);
            // The serpulo template preloads sectorSize=3; this test narrows it to erekir's 2.
            assertEquals(3, planet.fields.get("sectorSize").getAsInt(),
                "new planets start from the serpulo template");
            controller.setField(planet, "sectorSize", new com.google.gson.JsonPrimitive(2));
            for (int index : new int[] {5, 40, 88}) {
                ContentRecord sector = controller.addContent(ContentType.sector, 0d, 0d);
                controller.setField(sector, "planet", new com.google.gson.JsonPrimitive(planet.name));
                controller.setField(sector, "sector", new com.google.gson.JsonPrimitive(index));
            }

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.showWorkspace(Workspace.cosmos);
            shell.applyCss();
            shell.layout();
            controller.select(planet);

            var cosmos = shell.cosmosPane();
            assertTrue(cosmos.previewRendered(), "the preview must draw the grid");
            assertEquals(93, cosmos.previewCapacity(), "sectorSize=2 -> 92 tiles + 1 slot");
            assertEquals(3, cosmos.previewOccupiedCount());
            // The other two workbench tabs: the planet appearance model and the system schematic
            // (sun + three vanilla siblings + this planet, via the serpulo template's parent).
            assertTrue(cosmos.previewAppearanceRendered(), "the appearance model must build");
            assertEquals(5, cosmos.previewSystemBodies(), "sun + erekir + tantros + serpulo + the planet");
            assertEquals(List.of("planet: " + planet.name,
                "sector: new-sector-1", "sector: new-sector-2", "sector: new-sector-3"),
                cosmos.flattenedTree());

            // The mesh sub-tables: planet mesh (NoiseMesh from the serpulo template) plus the
            // MultiMesh cloud with its two child layers.
            List<MeshEditor> editors = new ArrayList<>();
            collectMeshEditors(shell, editors);
            assertTrue(editors.size() >= 4, "mesh + cloudMesh + 2 cloud layers, saw " + editors.size());
            MeshEditor meshEditor = editors.stream()
                .filter(editor -> "NoiseMesh".equals(editor.currentType()))
                .findFirst().orElseThrow();
            meshEditor.editNumber("octaves", 5);
            assertEquals(5, planet.fields.get("mesh").getAsJsonObject().get("octaves").getAsInt(),
                "mesh sub-table edits write into the record");
            assertEquals(2, planet.fields.get("mesh").getAsJsonObject().get("seed").getAsInt(),
                "untouched template keys survive");

            Snapshotter.write(scene, Path.of("snapshots", "ui-cosmos.png"));
            // Capture the two other workbench tabs for visual verification.
            cosmos.selectPreviewTab(1);
            Snapshotter.write(scene, Path.of("snapshots", "ui-appearance.png"));
            cosmos.selectPreviewTab(2);
            Snapshotter.write(scene, Path.of("snapshots", "ui-system.png"));
            // Pixel guards: every workbench tab must actually draw something.
            assertTrue(cosmos.previewNonBackgroundPixels(0) > 300, "grid tab must draw tiles");
            assertTrue(cosmos.previewNonBackgroundPixels(1) > 500, "appearance tab must draw the planet");
            assertTrue(cosmos.previewNonBackgroundPixels(2) > 200, "system tab must draw orbits");
            System.out.println("cosmos smoke: capacity=" + cosmos.previewCapacity()
                + " occupied=" + cosmos.previewOccupiedCount()
                + " appearance=" + cosmos.previewAppearanceRendered()
                + " bodies=" + cosmos.previewSystemBodies()
                + " px grid=" + cosmos.previewNonBackgroundPixels(0)
                + " px appearance=" + cosmos.previewNonBackgroundPixels(1)
                + " px system=" + cosmos.previewNonBackgroundPixels(2));
        });
    }

    private static void collectMeshEditors(Node node, List<MeshEditor> out) {
        if (node instanceof MeshEditor editor) {
            out.add(editor);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectMeshEditors(child, out);
            }
        }
    }

    /** The board node id that shows a given content record. */
    private static String boardNodeId(ProjectController controller, String contentId) {
        return controller.board().nodes.stream()
            .filter(node -> contentId.equals(node.contentId))
            .findFirst().orElseThrow().id;
    }

    private static int countCards(Node node) {
        if (node instanceof ContentCard) {
            return 1;
        }
        if (node instanceof Parent parent) {
            int total = 0;
            for (Node child : parent.getChildrenUnmodifiable()) {
                total += countCards(child);
            }
            return total;
        }
        return 0;
    }

    private static int countInputPorts(Node node) {
        if (node instanceof ContentCard card) {
            return (int) card.ports().stream().filter(port -> port.fieldKey != null).count();
        }
        if (node instanceof Parent parent) {
            int total = 0;
            for (Node child : parent.getChildrenUnmodifiable()) {
                total += countInputPorts(child);
            }
            return total;
        }
        return 0;
    }
}
