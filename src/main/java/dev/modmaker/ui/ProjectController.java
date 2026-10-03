package dev.modmaker.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Ids;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.PlanetTemplates;
import dev.modmaker.core.fmt.ReferenceOps;
import dev.modmaker.core.io.AutoLayout;
import dev.modmaker.core.io.ExportReport;
import dev.modmaker.core.io.ImportReport;
import dev.modmaker.core.io.ModExporter;
import dev.modmaker.core.io.ModImporter;
import dev.modmaker.core.io.ModPackage;
import dev.modmaker.core.io.ModPackageReader;
import dev.modmaker.core.io.ProjectFactory;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.json.JsonPath;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;
import dev.modmaker.core.validate.Validator;

import javafx.geometry.Point2D;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything the UI does to a project in one place: open, import, edit, save, validate, build.
 *
 * <p>Views subscribe to change notifications instead of talking to the core directly, so the order of
 * a save, a validation run and a repaint stays consistent.
 */
public final class ProjectController {

    private final SchemaRegistry registry;
    private final Validator validator;

    private ModProject project;
    private Board board;
    private ContentRecord selected;

    private final List<Runnable> projectListeners = new ArrayList<>();
    private final List<Consumer<ContentRecord>> selectionListeners = new ArrayList<>();
    private Consumer<String> logSink = message -> {
    };

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    /**
     * Experimental: when on, the inspector's class dropdown only offers classes in the same branch
     * of the hierarchy as the current one, so a battery cannot be turned into an item bridge.
     */
    private boolean adaptiveClasses = true;

    /** One reversible user action. redo() re-applies what the user did. */
    public interface Command {
        void undo();

        void redo();
    }

    public ProjectController(SchemaRegistry registry) {
        this.registry = registry;
        this.validator = new Validator(registry);
    }

    public SchemaRegistry registry() {
        return registry;
    }

    public ModProject project() {
        return project;
    }

    public Board board() {
        return board;
    }

    public ContentRecord selected() {
        return selected;
    }

    public void onProjectChanged(Runnable listener) {
        projectListeners.add(listener);
    }

    public void onSelectionChanged(Consumer<ContentRecord> listener) {
        selectionListeners.add(listener);
    }

    public void setLogSink(Consumer<String> sink) {
        this.logSink = sink;
    }

    public void log(String message) {
        logSink.accept(message);
    }

    // --- project lifecycle ----------------------------------------------------------------------

    public ModProject createProject(String modName, Path root) throws IOException {
        project = ProjectFactory.create(modName, root);
        board = project.boards.isEmpty() ? null : project.boards.get(0);
        selected = null;
        clearHistory();
        log("new project: " + root);
        fireProjectChanged();
        return project;
    }

    public ModProject openProject(Path root) throws IOException {
        project = ProjectIo.open(root, registry);
        board = project.boards.isEmpty() ? null : project.boards.get(0);
        selected = null;
        clearHistory();
        log("opened project: " + root + " (" + project.contents.size() + " records, "
            + project.assets.size() + " assets)");
        fireProjectChanged();
        return project;
    }

    /** Reads a mod package (zip or folder) and unpacks it into a project folder. */
    public ModProject importPackage(Path source, Path destinationRoot) throws IOException {
        ModPackage pkg = ModPackageReader.read(source);
        ModImporter.Result result = new ModImporter(registry).importPackage(pkg);
        String internal = result.project().internalName();
        Path root = internal.isEmpty() ? destinationRoot : destinationRoot.resolve(internal);
        ProjectIo.unpack(result.project(), pkg, root);

        project = result.project();
        board = project.boards.isEmpty() ? null : project.boards.get(0);
        selected = null;
        clearHistory();
        ImportReport report = result.report();
        log("imported " + source);
        log("  " + report.summary());
        report.warnings.stream().limit(20).forEach(warning -> log("  warn: " + warning));
        fireProjectChanged();
        return project;
    }

    public void save() throws IOException {
        requireProject();
        ProjectIo.save(project, project.root);
        log("saved " + project.root);
    }

    // --- content --------------------------------------------------------------------------------

    /** Adds a content record of the given type and places it on the current board. */
    public ContentRecord addContent(ContentType type) {
        return addContent(type, null, null);
    }

    /** Adds content, placing its card at the given world position (or below the stack when null). */
    public ContentRecord addContent(ContentType type, Double worldX, Double worldY) {
        requireProject();
        String name = uniqueName(type);
        ContentRecord record = ContentRecord.create(Ids.uid("c"), type, name);

        String baseClass = registry.baseClassOf(type);
        if (baseClass != null && !baseClass.equals(type.defaultClass())) {
            // Only spell out "type" when it is not what the game would assume anyway.
            record.setClassName(baseClass);
        }
        // Templates prefill new records with vanilla default data (planets get serpulo's grid,
        // atmosphere and campaign flags) so they are complete instead of empty shells.
        com.google.gson.JsonObject template = PlanetTemplates.templateFor(type);
        if (template != null) {
            for (var entry : template.entrySet()) {
                record.fields.put(entry.getKey(), entry.getValue());
            }
            record.dirty = true;
        }
        project.contents.add(record);
        project.strings.put(stringKey(record, "name"), "en", name);
        project.strings.markBundleDirty("en");
        if (!project.locales.contains("en")) {
            project.locales.add(0, "en");
        }
        if (board != null) {
            CanvasNode node = new CanvasNode();
            node.id = Ids.uid("n");
            node.kind = CanvasNode.KIND_CONTENT;
            node.contentId = record.id;
            if (worldX != null && worldY != null) {
                node.x = worldX;
                node.y = worldY;
            } else {
                placeBelowAll(node);
            }
            board.nodes.add(node);

            ContentRecord added = record;
            CanvasNode placed = node;
            String stringsKey = stringKey(record, "name");
            pushCommand(new Command() {
                @Override
                public void undo() {
                    project.contents.remove(added);
                    project.strings.removeKey(stringsKey);
                    board.nodes.remove(placed);
                    board.edges.removeIf(edge -> placed.id.equals(edge.from) || placed.id.equals(edge.to));
                }

                @Override
                public void redo() {
                    project.contents.add(added);
                    project.strings.put(stringsKey, "en", added.name);
                    project.strings.markBundleDirty("en");
                    if (!board.nodes.contains(placed)) {
                        board.nodes.add(placed);
                    }
                }
            });
        }
        String templateName = PlanetTemplates.name(type);
        log("added " + type + " '" + name + "'"
            + (templateName != null ? " (from the " + templateName + " template)" : ""));
        fireProjectChanged();
        select(record);
        return record;
    }

    /** Default placement for a new card: below everything already on the board. */
    private void placeBelowAll(CanvasNode node) {
        node.x = AutoLayout.margin();
        double lowest = AutoLayout.margin();
        for (CanvasNode existing : board.nodes) {
            lowest = Math.max(lowest, existing.y + existing.h);
        }
        node.y = lowest + AutoLayout.gap();
    }

    /** Renaming changes the file the record is written to; references are by identity, not by name. */
    public void rename(ContentRecord record, String newName) {
        requireProject();
        if (record == null || newName == null || newName.equals(record.name)) {
            return;
        }
        String problem = NameRules.contentNameProblem(newName);
        if (problem != null) {
            log("cannot rename to '" + newName + "': " + problem);
            return;
        }
        String previous = record.name;
        record.name = newName;
        record.dirty = true;
        fireProjectChanged();
        log("renamed '" + previous + "' to '" + newName + "'; the file moves on save");
    }

    public void deleteContent(ContentRecord record) {
        requireProject();
        if (record == null) {
            return;
        }
        // Capture everything needed to restore the record exactly: the card, its edges, and for
        // nested records the host's field tree before removal.
        CanvasNode node = board == null ? null : board.nodes.stream()
            .filter(candidate -> record.id.equals(candidate.contentId))
            .findFirst().orElse(null);
        List<CanvasEdge> touching = board == null || node == null
            ? List.of()
            : board.edges.stream()
                .filter(edge -> node.id.equals(edge.from) || node.id.equals(edge.to))
                .toList();
        ContentRecord owner = record.isInline() ? project.contentById(record.inlineOwnerId) : null;
        com.google.gson.JsonObject ownerBefore = owner == null ? null : owner.tree();

        if (record.isInline() && record.inlineOwnerId != null) {
            // Nested content only exists inside its host, so removing the node removes the object.
            if (owner != null) {
                com.google.gson.JsonObject tree = owner.tree();
                if (JsonPath.remove(tree, record.inlinePath)) {
                    owner.applyTree(tree);
                    owner.dirty = true;
                }
            }
        }
        project.contents.remove(record);
        project.strings.removeKey(stringKey(record, "name"));
        if (board != null) {
            board.nodes.removeIf(candidate -> record.id.equals(candidate.contentId));
            if (node != null) {
                board.edges.removeIf(edge -> node.id.equals(edge.from) || node.id.equals(edge.to));
            }
        }
        selected = null;

        pushCommand(new Command() {
            @Override
            public void undo() {
                project.contents.add(record);
                project.strings.put(stringKey(record, "name"), "en", record.name);
                if (node != null && !board.nodes.contains(node)) {
                    board.nodes.add(node);
                }
                board.edges.addAll(touching);
                if (owner != null && ownerBefore != null) {
                    owner.applyTree(ownerBefore);
                    owner.dirty = true;
                }
            }

            @Override
            public void redo() {
                if (record.isInline() && owner != null) {
                    com.google.gson.JsonObject tree = owner.tree();
                    if (JsonPath.remove(tree, record.inlinePath)) {
                        owner.applyTree(tree);
                        owner.dirty = true;
                    }
                }
                project.contents.remove(record);
                project.strings.removeKey(stringKey(record, "name"));
                board.nodes.removeIf(candidate -> record.id.equals(candidate.contentId));
                board.edges.removeIf(edge -> touching.contains(edge));
            }
        });

        log("deleted " + record.type + " '" + record.name + "'");
        fireProjectChanged();
    }

    /**
     * Draws a reference: writes the source content's name into the target's field and adds the edge.
     * The source name is written bare - the game resolves it against the current mod first, and
     * vanilla targets must not be prefixed.
     */
    public boolean connect(String fromNodeId, String toNodeId, String fieldKey) {
        requireProject();
        if (board == null) {
            return false;
        }
        CanvasNode fromNode = nodeById(fromNodeId);
        CanvasNode toNode = nodeById(toNodeId);
        if (fromNode == null || toNode == null) {
            return false;
        }
        ContentRecord from = project.contentById(fromNode.contentId);
        ContentRecord to = project.contentById(toNode.contentId);
        if (from == null || to == null || from == to) {
            return false;
        }
        boolean exists = board.edges.stream().anyMatch(edge ->
            fromNodeId.equals(edge.from) && toNodeId.equals(edge.to) && fieldKey.equals(edge.fieldKey));
        if (exists) {
            log("that reference already exists");
            return false;
        }
        FieldDef field = referenceField(to, fieldKey);
        if (field == null) {
            log(fieldKey + " is not a reference field of " + to.name);
            return false;
        }

        String sourceName = from.name;
        ReferenceOps.Revert revert = ReferenceOps.apply(to, field, sourceName);
        to.dirty = true;
        CanvasEdge edge = new CanvasEdge();
        edge.id = Ids.uid("e");
        edge.from = fromNodeId;
        edge.to = toNodeId;
        edge.fieldKey = fieldKey;
        edge.label = sourceName;
        board.edges.add(edge);

        pushCommand(new Command() {
            @Override
            public void undo() {
                ReferenceOps.revert(to, field, revert);
                to.dirty = true;
                board.edges.remove(edge);
            }

            @Override
            public void redo() {
                ReferenceOps.apply(to, field, sourceName);
                to.dirty = true;
                if (!board.edges.contains(edge)) {
                    board.edges.add(edge);
                }
            }
        });
        log(from.name + " -> " + to.name + "." + fieldKey);
        fireProjectChanged();
        return true;
    }

    /** Removes a reference edge and its field value. */
    public void disconnect(CanvasEdge edge) {
        requireProject();
        if (edge == null || !board.edges.contains(edge)) {
            return;
        }
        ContentRecord from = project.contentById(
            board.nodes.stream().filter(node -> node.id.equals(edge.from))
                .findFirst().map(node -> node.contentId).orElse(null));
        ContentRecord to = project.contentById(
            board.nodes.stream().filter(node -> node.id.equals(edge.to))
                .findFirst().map(node -> node.contentId).orElse(null));
        if (from == null || to == null) {
            board.edges.remove(edge);
            fireProjectChanged();
            return;
        }
        FieldDef field = referenceField(to, edge.fieldKey);
        boolean removed = field != null && ReferenceOps.remove(to, field, from.name);
        if (removed) {
            to.dirty = true;
        }
        board.edges.remove(edge);

        pushCommand(new Command() {
            @Override
            public void undo() {
                if (field != null) {
                    ReferenceOps.apply(to, field, from.name);
                    to.dirty = true;
                }
                if (!board.edges.contains(edge)) {
                    board.edges.add(edge);
                }
            }

            @Override
            public void redo() {
                if (field != null) {
                    ReferenceOps.remove(to, field, from.name);
                    to.dirty = true;
                }
                board.edges.remove(edge);
            }
        });
        log("removed " + from.name + " -> " + to.name + "." + edge.fieldKey);
        fireProjectChanged();
    }

    /** Records a finished card drag so it can be undone (positions are already applied). */
    public void commitMoves(Map<String, Point2D> before, Map<String, double[]> after) {
        if (before.isEmpty()) {
            return;
        }
        pushCommand(new Command() {
            private void apply(Map<String, double[]> positions) {
                if (board == null) {
                    return;
                }
                for (CanvasNode node : board.nodes) {
                    double[] position = positions.get(node.id);
                    if (position != null) {
                        node.x = position[0];
                        node.y = position[1];
                    }
                }
            }

            @Override
            public void undo() {
                Map<String, double[]> previous = new java.util.LinkedHashMap<>();
                before.forEach((nodeId, point) -> previous.put(nodeId, new double[] {point.getX(), point.getY()}));
                apply(previous);
            }

            @Override
            public void redo() {
                apply(after);
            }
        });
    }

    // --- undo / redo ----------------------------------------------------------------------------

    public boolean adaptiveClasses() {
        return adaptiveClasses;
    }

    public void setAdaptiveClasses(boolean value) {
        if (adaptiveClasses != value) {
            adaptiveClasses = value;
            log(value ? "adaptive classes on (experimental)" : "adaptive classes off");
            fireProjectChanged();
        }
    }

    public void pushCommand(Command command) {
        undoStack.push(command);
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        requireProject();
        Command command = undoStack.poll();
        if (command == null) {
            return;
        }
        command.undo();
        redoStack.push(command);
        log("undo");
        fireProjectChanged();
    }

    public void redo() {
        requireProject();
        Command command = redoStack.poll();
        if (command == null) {
            return;
        }
        command.redo();
        undoStack.push(command);
        log("redo");
        fireProjectChanged();
    }

    private void clearHistory() {
        undoStack.clear();
        redoStack.clear();
    }

    private FieldDef referenceField(ContentRecord record, String fieldKey) {
        String className = registry.resolveClass(record.type, record.className());
        if (className == null) {
            return null;
        }
        for (FieldDef def : registry.fieldsOf(className)) {
            if (def.name().equals(fieldKey)) {
                return def.isReference() ? def : null;
            }
        }
        return null;
    }

    private CanvasNode nodeById(String nodeId) {
        return board == null ? null : board.nodes.stream()
            .filter(node -> node.id.equals(nodeId))
            .findFirst().orElse(null);
    }

    /** Renaming changes the file the record is written to; references are by identity, not by name. */

    /** Sets a field on a record, marking it dirty so it is re-serialised on save. */
    public void setField(ContentRecord record, String field, JsonElement value) {
        requireProject();
        if (record == null) {
            return;
        }
        if (value == null) {
            record.fields.remove(field);
        } else {
            record.fields.put(field, value);
        }
        record.dirty = true;
        fireProjectChanged();
    }

    public void setString(String key, String locale, String value) {
        requireProject();
        project.strings.put(key, locale, value);
        project.strings.markBundleDirty(locale);
        fireProjectChanged();
    }

    public void select(ContentRecord record) {
        selected = record;
        selectionListeners.forEach(listener -> listener.accept(record));
    }

    public Board addBoard(String name) {
        requireProject();
        Board created = new Board();
        created.id = Ids.uid("b");
        created.name = name;
        created.file = name.toLowerCase().replaceAll("[^a-z0-9\\-_]+", "-") + ".canvas";
        project.boards.add(created);
        board = created;
        fireProjectChanged();
        return created;
    }

    public void setBoard(Board next) {
        board = next;
        fireProjectChanged();
    }

    // --- layout, validation, build --------------------------------------------------------------

    public void tidyLayout() {
        requireProject();
        if (board != null) {
            AutoLayout.tidy(project, board);
        }
        fireProjectChanged();
    }

    public List<Issue> validate() {
        requireProject();
        List<Issue> issues = validator.validate(project);
        long errors = issues.stream().filter(Issue::isError).count();
        log("validation: " + errors + " errors, " + (issues.size() - errors) + " warnings");
        return issues;
    }

    public ExportReport buildFolder(Path destination) throws IOException {
        requireProject();
        List<Issue> issues = validator.validate(project);
        ProjectIo.save(project, project.root);
        ExportReport report = ModExporter.buildFolder(project, destination, issues);
        log("build: " + report.summary());
        return report;
    }

    public ExportReport buildZip(Path zipFile) throws IOException {
        requireProject();
        List<Issue> issues = validator.validate(project);
        ProjectIo.save(project, project.root);
        ExportReport report = ModExporter.buildZip(project, zipFile, issues);
        log("build: " + report.summary());
        return report;
    }

    // --- small conveniences the UI needs --------------------------------------------------------

    /** The bundle key for one of the four localisable fields of a record. */
    public String stringKey(ContentRecord record, String field) {
        requireProject();
        return NameRules.bundleKey(record.type, record.fullName(project.internalName()), field);
    }

    public String stringValue(ContentRecord record, String field, String locale) {
        String value = project.strings.get(stringKey(record, field), locale);
        return value == null ? "" : value;
    }

    public String classNameOf(ContentRecord record) {
        String resolved = registry.resolveClass(record.type, record.className());
        return resolved == null ? record.type.name() : resolved;
    }

    /** What a card and lists show: the English string, else the record name. */
    public String displayName(ContentRecord record) {
        String value = stringValue(record, "name", "en");
        return value == null || value.isBlank() ? record.name : value;
    }

    /** Localised content type name, e.g. 物品 for item under the Chinese UI. */
    public String typeName(ContentType type) {
        return Messages.t("ctype." + type.name());
    }

    /** The second line of a card / inspector header: localised type plus the class with its label. */
    public String subtitleOf(ContentRecord record) {
        String className = classNameOf(record);
        String base = registry.baseClassOf(record.type);
        // "方块 · Block" says the same thing twice; only spell the class out when it is a subclass.
        if (className.equals(base) || className.equals(record.type.defaultClass())) {
            return typeName(record.type);
        }
        return typeName(record.type) + " · " + Labels.clazz(className);
    }

    private void fireProjectChanged() {
        projectListeners.forEach(Runnable::run);
    }

    private void requireProject() {
        if (project == null) {
            throw new IllegalStateException("no project is open");
        }
    }

    private String uniqueName(ContentType type) {
        Set<String> taken = new LinkedHashSet<>();
        project.contents.stream()
            .filter(record -> record.type == type && !record.isInline())
            .forEach(record -> taken.add(record.name));
        String base = "new-" + type.name();
        int index = 1;
        while (taken.contains(base + "-" + index)) {
            index++;
        }
        return base + "-" + index;
    }
}
