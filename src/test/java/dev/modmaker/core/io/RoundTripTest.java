package dev.modmaker.core.io;

import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;
import dev.modmaker.core.validate.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The acceptance gate for the whole format layer: a real 955-file mod must survive
 * import -&gt; project folder -&gt; build byte for byte, and editing one content file must leave every
 * other file alone.
 */
class RoundTripTest {

    private static final Path MOD_ROOT =
        dev.modmaker.core.io.TestFixtures.realModRoot();
    private static final Path WORK = Paths.get("build", "roundtrip").toAbsolutePath();

    private static SchemaRegistry registry;

    @BeforeAll
    static void checkFixtures() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    @Test
    void realModRebuildsByteForByte() throws IOException {
        Path projectRoot = WORK.resolve("project");
        Path buildDir = WORK.resolve("build");
        deleteRecursively(WORK);

        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        ModImporter.Result imported = new ModImporter(registry).importPackage(pkg);
        ProjectIo.unpack(imported.project(), pkg, projectRoot);

        List<Issue> issues = new Validator(registry).validate(imported.project());
        ExportReport report = ModExporter.buildFolder(imported.project(), buildDir, issues);
        System.out.println("build: " + report.summary());
        issues.stream().filter(Issue::isError).limit(10).forEach(issue ->
            System.out.println("  ERROR " + issue.message()));
        issues.stream().filter(issue -> !issue.isError()).limit(5).forEach(issue ->
            System.out.println("  warn " + issue.message()));
        assertFalse(report.hasErrors(), "the real mod must build without validation errors");
        assertNotNull(report.target());

        Path builtMod = buildDir.resolve(imported.project().internalName());
        List<String> missing = new ArrayList<>();
        List<String> different = new ArrayList<>();
        List<String> original = new ArrayList<>();
        for (Path file : filesOf(MOD_ROOT)) {
            String relative = relative(MOD_ROOT, file);
            original.add(relative);
            Path built = builtMod.resolve(relative);
            if (!Files.exists(built)) {
                missing.add(relative);
            } else if (!Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(built))) {
                different.add(relative);
            }
        }
        List<String> extra = new ArrayList<>();
        for (Path file : filesOf(builtMod)) {
            String relative = relative(builtMod, file);
            if (!original.contains(relative)) {
                extra.add(relative);
            }
        }

        System.out.println("original=" + original.size() + " missing=" + missing.size()
            + " different=" + different.size() + " extra=" + extra.size());
        different.stream().limit(10).forEach(name -> System.out.println("  DIFF " + name));
        extra.stream().limit(10).forEach(name -> System.out.println("  EXTRA " + name));

        assertEquals(List.of(), missing, "every original file must be in the build");
        assertEquals(List.of(), different, "untouched files must be byte-identical");
        assertEquals(List.of(), extra, "the build must not add files the mod never had");
        // The mod ships an empty sprites-override/ directory; it must survive the round trip.
        assertTrue(Files.isDirectory(builtMod.resolve("sprites-override")),
            "empty directories are part of the package");
    }

    @Test
    void packagingToZipKeepsTheModLoadable() throws IOException {
        deleteRecursively(WORK.resolve("zip-work"));
        Path projectRoot = WORK.resolve("zip-work").resolve("project");
        Path zip = WORK.resolve("zip-work").resolve("mod.zip");

        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        ModImporter.Result imported = new ModImporter(registry).importPackage(pkg);
        ProjectIo.unpack(imported.project(), pkg, projectRoot);

        ExportReport report = ModExporter.buildZip(imported.project(), zip, List.of());
        System.out.println("zip: " + report.summary());
        assertTrue(Files.size(zip) > 1_000_000, "the archive should carry the real assets");
        assertEquals(filesOf(MOD_ROOT).size(), report.files().size(),
            "the archive must contain exactly the mod's files");

        // The archive must read back as a mod: single wrapping folder unwrapped, same content.
        ModPackage reopened = ModPackageReader.read(zip);
        assertEquals(pkg.contents.size(), reopened.contents.size());
        assertFalse(reopened.rootPrefix.isEmpty(), "the archive keeps one wrapping folder");
        assertEquals(imported.project().internalName() + "/", reopened.rootPrefix);
        assertEquals("社会主义工业化", reopened.meta.name);
    }

    @Test
    void editingOneContentLeavesEveryOtherFileUntouched() throws IOException {
        Path work = WORK.resolve("edit-work");
        deleteRecursively(work);
        Path projectRoot = work.resolve("project");

        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        ModImporter.Result imported = new ModImporter(registry).importPackage(pkg);
        ModProject project = imported.project();
        ProjectIo.unpack(project, pkg, projectRoot);

        ContentRecord target = project.contents.stream()
            .filter(record -> !record.isInline() && record.type == dev.modmaker.core.fmt.ContentType.item)
            .filter(record -> record.rawPath != null)
            .findFirst()
            .orElseThrow();
        String originalPath = target.rawPath;
        target.fields.put("hardness", new JsonPrimitive(9));
        target.dirty = true;

        ProjectIo.save(project, projectRoot);
        assertFalse(target.dirty, "saving clears the dirty flag");
        assertEquals(originalPath, target.rawPath);

        // Exactly one file changed on disk.
        List<String> changed = new ArrayList<>();
        for (Path file : filesOf(MOD_ROOT)) {
            String relative = relative(MOD_ROOT, file);
            Path saved = projectRoot.resolve(relative);
            if (!Files.exists(saved) || !Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(saved))) {
                changed.add(relative);
            }
        }
        assertEquals(List.of(originalPath), changed,
            "only the edited content file may differ, saw " + changed);

        // Reopening sees the edit, and a build carries it without touching anything else.
        ModProject reopened = ProjectIo.open(projectRoot, registry);
        ContentRecord reread = reopened.contents.stream()
            .filter(record -> record.rawPath != null && record.rawPath.equals(originalPath))
            .findFirst().orElseThrow();
        assertEquals(9, reread.fields.get("hardness").getAsInt());
        assertFalse(reread.dirty, "content read from disk starts clean");

        Path buildDir = work.resolve("build");
        ModExporter.buildFolder(reopened, buildDir, List.of());
        String builtText = Files.readString(buildDir.resolve(reopened.internalName()).resolve(originalPath));
        assertTrue(builtText.contains("9"), "the built file carries the edit");
    }

    @Test
    void boardsRoundTripThroughJsonCanvas() throws IOException {
        Path work = WORK.resolve("board-work");
        deleteRecursively(work);
        Path projectRoot = work.resolve("project");

        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        ModImporter.Result imported = new ModImporter(registry).importPackage(pkg);
        ProjectIo.unpack(imported.project(), pkg, projectRoot);

        ModProject reopened = ProjectIo.open(projectRoot, registry);
        assertEquals(imported.project().boards.size(), reopened.boards.size());
        var original = imported.project().boards.get(0);
        var restored = reopened.boards.get(0);
        assertEquals(original.nodes.size(), restored.nodes.size());
        assertEquals(original.edges.size(), restored.edges.size());
        // Node positions and the content they point at survive, and the canvas stays readable.
        assertEquals(original.nodes.get(0).contentId, restored.nodes.get(0).contentId);
        assertEquals(original.nodes.get(0).x, restored.nodes.get(0).x, 0.001);
        String canvas = Files.readString(
            projectRoot.resolve(ProjectIo.AUTHORING_DIR).resolve(ProjectIo.BOARDS_DIR).resolve("main.canvas"));
        assertTrue(canvas.contains("\"mindustry\""));
        assertTrue(canvas.contains("\"nodes\""));
    }

    private static List<Path> filesOf(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
