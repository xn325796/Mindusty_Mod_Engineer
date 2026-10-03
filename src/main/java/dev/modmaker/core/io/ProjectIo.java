package dev.modmaker.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.PropertiesFile;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.json.JsonPath;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The project folder is a real mod folder plus an authoring directory:
 *
 * <pre>
 * &lt;project&gt;/mod.json          real meta file (verbatim until it is edited)
 * &lt;project&gt;/content/**         real content files (verbatim until they are edited)
 * &lt;project&gt;/bundles/**         real bundle files
 * &lt;project&gt;/sprites|scripts|... real assets
 * &lt;project&gt;/modmaker/project.json   board list, record ids, provenance
 * &lt;project&gt;/modmaker/boards/*.canvas JSON Canvas boards
 * </pre>
 *
 * <p>Because the mod files themselves carry the state, a project folder can be dropped straight into
 * the game's mods directory to test it, saving only rewrites what actually changed, and building is a
 * plain copy. Records that were never touched keep their exact original bytes.
 */
public final class ProjectIo {

    public static final String AUTHORING_DIR = "modmaker";
    public static final String PROJECT_FILE = "project.json";
    public static final String BOARDS_DIR = "boards";

    private ProjectIo() {
    }

    // --- write ----------------------------------------------------------------------------------

    /** Materialises an imported package as a project folder, keeping every file as it was shipped. */
    public static void unpack(ModProject project, ModPackage pkg, Path root) throws IOException {
        Files.createDirectories(root);

        String metaName = pkg.metaFile.isEmpty() ? "mod.hjson" : pkg.metaFile;
        project.metaFile = metaName;
        // Verbatim, BOM included; parsers strip the BOM themselves when they read it back.
        String metaText = pkg.metaText;
        if (metaText.isEmpty()) {
            project.metaText = Json.write(ModMetaCodec.toJson(project.meta)) + "\n";
        } else {
            project.metaText = metaText;
        }
        project.metaDirty = false;

        for (ModPackage.SourceContent content : pkg.contents) {
            writeText(root.resolve(content.path), content.text);
        }
        for (Map.Entry<String, String> bundle : pkg.bundleFiles.entrySet()) {
            writeText(root.resolve(bundle.getKey()), bundle.getValue());
            String locale = bundleLocale(bundle.getKey());
            project.strings.bundleRaw.put(locale, bundle.getValue());
        }
        for (Map.Entry<String, byte[]> asset : pkg.assetFiles.entrySet()) {
            Path target = safeResolve(root, asset.getKey());
            if (target == null) {
                pkg.skipped.add(asset.getKey() + " (path escapes the project folder)");
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, asset.getValue());
        }
        // Empty directories are part of the package too (an empty sprites-override/, for instance).
        for (String dir : pkg.emptyDirs) {
            Path target = safeResolve(root, dir);
            if (target == null) {
                pkg.skipped.add(dir + " (path escapes the project folder)");
                continue;
            }
            Files.createDirectories(target);
        }
        project.root = root;
        save(project, root);
    }

    /** Resolves a path from an untrusted package, or null when it would escape the project root. */
    static Path safeResolve(Path root, String relative) {
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        Path target = root.resolve(relative).normalize();
        Path normalizedRoot = root.normalize();
        return target.startsWith(normalizedRoot) ? target : null;
    }

    /** Syncs changed state to disk. Untouched records and bundles are rewritten byte for byte. */
    public static void save(ModProject project, Path root) throws IOException {
        Files.createDirectories(root);
        project.root = root;

        writeMeta(project, root);
        Set<Path> expectedContent = writeContent(project, root);
        pruneManaged(root, root.resolve("content"), expectedContent, project.assetPaths(),
            Set.of(".json", ".hjson"));
        writeBundles(project, root);
        writeAuthoringLayer(project, root);
    }

    private static void writeMeta(ModProject project, Path root) throws IOException {
        String metaName = project.metaFile == null || project.metaFile.isEmpty()
            ? "mod.hjson"
            : project.metaFile;
        Path target = root.resolve(metaName);
        String text = !project.metaDirty && project.metaText != null && !project.metaText.isEmpty()
            ? project.metaText
            : Json.write(ModMetaCodec.toJson(project.meta)) + "\n";
        writeText(target, text);
        project.metaText = text;
        project.metaDirty = false;
    }

    private static Set<Path> writeContent(ModProject project, Path root) throws IOException {
        Set<Path> expected = new LinkedHashSet<>();
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                continue;
            }
            boolean inlineChanged = project.contents.stream()
                .anyMatch(inline -> record.id.equals(inline.inlineOwnerId) && inline.dirty);
            String previousPath = record.rawPath;

            Path target = targetPath(root, record);
            String text = !record.dirty && !inlineChanged && record.raw != null
                ? record.raw
                : serialize(project, record);
            writeText(target, text);

            record.rawPath = root.relativize(target).toString().replace('\\', '/');
            record.raw = text;
            record.dirty = false;
            expected.add(target.toAbsolutePath().normalize());

            if (previousPath != null && !previousPath.equals(record.rawPath)) {
                Files.deleteIfExists(root.resolve(previousPath));
            }
        }
        project.contents.stream().filter(ContentRecord::isInline).forEach(record -> record.dirty = false);
        return expected;
    }

    /**
     * Where a record's file belongs. Content that came from a package keeps the folder it was
     * imported from (mods nest content in their own subfolders, and an edit must not move it);
     * renaming only changes the file name inside that folder.
     */
    private static Path targetPath(Path root, ContentRecord record) {
        if (record.rawPath == null) {
            return root.resolve(canonicalContentPath(record));
        }
        Path existing = root.resolve(record.rawPath);
        Path parent = existing.getParent();
        if (parent == null) {
            return root.resolve(canonicalContentPath(record));
        }
        Path target = parent.resolve(record.name + "." + record.ext);
        if (!target.normalize().startsWith(root.normalize())) {
            return root.resolve(canonicalContentPath(record));
        }
        return target;
    }

    /**
     * Writes only the locales that actually have a bundle on disk, plus any the editor touched.
     * A locale is never invented: a mod that ships only bundle_zh_CN.properties keeps exactly that
     * one file.
     */
    private static void writeBundles(ModProject project, Path root) throws IOException {
        Set<Path> expected = new LinkedHashSet<>();
        Set<String> locales = new LinkedHashSet<>(project.strings.bundleFiles.keySet());
        for (String locale : project.locales) {
            if (project.strings.isBundleDirty(locale)) {
                locales.add(locale);
            }
        }
        locales.addAll(project.strings.extra.keySet());

        for (String locale : locales) {
            String fileName = project.strings.bundleFiles.getOrDefault(locale, BundleCodec.fileNameOf(locale));
            Path target = root.resolve("bundles").resolve(fileName);
            String text;
            if (!project.strings.isBundleDirty(locale) && project.strings.bundleRaw.containsKey(locale)) {
                text = project.strings.bundleRaw.get(locale);
            } else {
                text = renderBundle(project, locale);
            }
            writeText(target, text);
            project.strings.bundleRaw.put(locale, text);
            project.strings.bundleFiles.put(locale, fileName);
            project.strings.bundleDirty.put(locale, false);
            expected.add(target.toAbsolutePath().normalize());
        }
        pruneManaged(root, root.resolve("bundles"), expected, project.assetPaths(),
            Set.of(".properties"));
    }

    private static String renderBundle(ModProject project, String locale) {
        PropertiesFile file = new PropertiesFile();
        project.strings.values.entrySet().stream()
            .filter(entry -> entry.getValue().containsKey(locale)
                && entry.getValue().get(locale) != null
                && !entry.getValue().get(locale).isEmpty())
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> file.put(entry.getKey(), entry.getValue().get(locale)));
        Map<String, String> extras = project.strings.extra.get(locale);
        if (extras != null) {
            extras.forEach(file::put);
        }
        return file.write();
    }

    /**
     * Removes files this tool manages that no longer correspond to anything in the project.
     * Files that are assets of the project (unknown content folders, non-standard names) are never
     * touched, so nothing a mod ships can be lost by saving.
     */
    private static void pruneManaged(Path root, Path folder, Set<Path> expected,
        Set<String> protectedPaths, Set<String> extensions) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> candidates = walk.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
                if (!extensions.contains(extension)) {
                    continue;
                }
                if (folder.getFileName().toString().equals("bundles") && !BundleCodec.isBundleFile(name)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (protectedPaths.contains(relative)) {
                    continue;
                }
                if (!expected.contains(file.toAbsolutePath().normalize())) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static String serialize(ModProject project, ContentRecord record) {
        JsonObject object = new JsonObject();
        record.fields.forEach(object::add);
        for (ContentRecord inline : project.contents) {
            if (!record.id.equals(inline.inlineOwnerId)) {
                continue;
            }
            JsonObject inlineObject = new JsonObject();
            inline.fields.forEach(inlineObject::add);
            JsonPath.set(object, inline.inlinePath, inlineObject);
            inline.dirty = false;
        }
        return Json.write(object) + "\n";
    }

    private static void writeAuthoringLayer(ModProject project, Path root) throws IOException {
        Path authoring = root.resolve(AUTHORING_DIR);
        Path boardsDir = authoring.resolve(BOARDS_DIR);
        Files.createDirectories(boardsDir);

        JsonObject meta = new JsonObject();
        meta.addProperty("format", ModProject.FORMAT);
        meta.addProperty("schemaProfile", project.schemaProfile);
        meta.addProperty("importedFrom", project.importedFrom);
        meta.addProperty("metaFile", project.metaFile);

        JsonObject records = new JsonObject();
        for (ContentRecord record : project.contents) {
            String key = recordKey(project, record);
            if (key != null) {
                records.addProperty(key, record.id);
            }
        }
        meta.add("records", records);

        JsonArray boards = new JsonArray();
        for (Board board : project.boards) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", board.id);
            entry.addProperty("name", board.name);
            entry.addProperty("file", board.file);
            JsonObject viewport = new JsonObject();
            viewport.addProperty("x", board.viewport.x);
            viewport.addProperty("y", board.viewport.y);
            viewport.addProperty("zoom", board.viewport.zoom);
            entry.add("viewport", viewport);
            boards.add(entry);
            writeText(boardsDir.resolve(board.file), Json.write(JsonCanvas.toCanvas(project, board)) + "\n");
        }
        meta.add("boards", boards);
        writeText(authoring.resolve(PROJECT_FILE), Json.write(meta) + "\n");
    }

    // --- read -----------------------------------------------------------------------------------

    /** Opens a project folder: content comes from the real mod files, boards from modmaker/. */
    public static ModProject open(Path root, SchemaRegistry registry) throws IOException {
        ModPackage pkg = ModPackageReader.read(root);
        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        project.root = root;

        Path projectFile = root.resolve(AUTHORING_DIR).resolve(PROJECT_FILE);
        if (!Files.exists(projectFile)) {
            return project;
        }
        JsonObject meta = Json.parseObject(Json.readFile(projectFile));
        if (meta.has("schemaProfile")) {
            project.schemaProfile = meta.get("schemaProfile").getAsString();
        }
        if (meta.has("importedFrom")) {
            project.importedFrom = meta.get("importedFrom").getAsString();
        }
        if (meta.has("metaFile") && !meta.get("metaFile").getAsString().isEmpty()) {
            project.metaFile = meta.get("metaFile").getAsString();
        }

        // Restore record ids so saved node positions and edges still line up. File-backed records
        // are restored first and their old->new mapping is applied to inline links, because an
        // inline record points at its owner by id - remapping in one pass would orphan every
        // nested record (the owner's id changes before the children look it up).
        JsonObject records = meta.getAsJsonObject("records");
        if (records != null) {
            Map<String, String> remapped = new LinkedHashMap<>();
            for (ContentRecord record : project.contents) {
                if (record.isInline() || record.rawPath == null) {
                    continue;
                }
                if (records.has(record.rawPath)) {
                    remapped.put(record.id, records.get(record.rawPath).getAsString());
                }
            }
            for (ContentRecord record : project.contents) {
                if (record.isInline()) {
                    record.inlineOwnerId = remapped.getOrDefault(record.inlineOwnerId, record.inlineOwnerId);
                }
            }
            for (ContentRecord record : project.contents) {
                String key = recordKey(project, record);
                if (key != null && records.has(key)) {
                    record.id = records.get(key).getAsString();
                }
            }
        }

        JsonArray boards = meta.getAsJsonArray("boards");
        if (boards != null && !boards.isEmpty()) {
            List<Board> loaded = new ArrayList<>();
            for (JsonElement element : boards) {
                JsonObject entry = element.getAsJsonObject();
                String file = entry.get("file").getAsString();
                Path canvasFile = root.resolve(AUTHORING_DIR).resolve(BOARDS_DIR).resolve(file);
                String id = entry.has("id") ? entry.get("id").getAsString() : dev.modmaker.core.fmt.Ids.uid("b");
                Board board = Files.exists(canvasFile)
                    ? JsonCanvas.fromCanvas(Json.readFile(canvasFile), file, id)
                    : new Board();
                // The board name lives in project.json, not in the canvas file; restore it so a
                // reopen cycle does not drift "Main" to the file's base name.
                if (entry.has("name") && !entry.get("name").getAsString().isBlank()) {
                    board.name = entry.get("name").getAsString();
                }
                if (entry.has("viewport")) {
                    JsonObject viewport = entry.getAsJsonObject("viewport");
                    board.viewport.x = viewport.get("x").getAsDouble();
                    board.viewport.y = viewport.get("y").getAsDouble();
                    board.viewport.zoom = viewport.get("zoom").getAsDouble();
                }
                loaded.add(board);
            }
            project.boards = loaded;
        }
        return project;
    }

    // --- helpers --------------------------------------------------------------------------------

    private static String canonicalContentPath(ContentRecord record) {
        return "content/" + record.type.folderName() + "/" + record.name + "." + record.ext;
    }

    private static String bundleLocale(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String locale = BundleCodec.localeOf(fileName);
        return locale.isEmpty() ? "en" : locale;
    }

    private static void writeText(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        // Write-then-move so a crash mid-save can never leave a half-written mod file behind.
        Path temp = target.resolveSibling(target.getFileName() + ".mm-tmp");
        Files.writeString(temp, text == null ? "" : text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    /**
     * Identity of a record in the authoring layer: its file path, or for nested content the host's
     * path plus the position inside it.
     */
    private static String recordKey(ModProject project, ContentRecord record) {
        if (record.isInline()) {
            ContentRecord owner = project.contentById(record.inlineOwnerId);
            return owner == null || owner.rawPath == null
                ? null
                : owner.rawPath + "#" + record.inlinePath;
        }
        return record.rawPath;
    }
}
