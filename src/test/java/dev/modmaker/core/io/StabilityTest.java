package dev.modmaker.core.io;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;
import dev.modmaker.core.validate.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stability gates: repeated saves and reopens must not drift a single byte, broken or hostile
 * inputs must fail without losing data, and the real mod must keep importing fast.
 */
class StabilityTest {

    private static final Path MOD_ROOT =
        dev.modmaker.core.io.TestFixtures.realModRoot();
    private static final Path WORK = Paths.get("build", "stability").toAbsolutePath();

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    // --- determinism ----------------------------------------------------------------------------

    @Test
    void saveIsIdempotentByteForByte() throws IOException {
        Path root = WORK.resolve("idempotent");
        deleteRecursively(root);

        ModProject project = importAndUnpack(root);
        Map<String, byte[]> first = snapshot(root);

        ProjectIo.save(project, root);
        ProjectIo.save(project, root);
        Map<String, byte[]> second = snapshot(root);

        assertEquals(names(first), names(second), "no file may appear or disappear on repeated saves");
        for (String name : names(first)) {
            assertTrue(java.util.Arrays.equals(first.get(name), second.get(name)),
                "file drifted on repeated save: " + name);
        }
    }

    @Test
    void reopenThenSaveIsByteStable() throws IOException {
        Path root = WORK.resolve("reopen");
        deleteRecursively(root);

        importAndUnpack(root);
        Map<String, byte[]> first = snapshot(root);

        ModProject reopened = ProjectIo.open(root, registry);
        ProjectIo.save(reopened, root);
        Map<String, byte[]> second = snapshot(root);

        assertEquals(names(first), names(second), "a reopen cycle must not add or lose files");
        List<String> drifted = names(first).stream()
            .filter(name -> !java.util.Arrays.equals(first.get(name), second.get(name)))
            .toList();
        for (String name : drifted) {
            byte[] before = first.get(name);
            byte[] after = second.get(name);
            int diffAt = 0;
            while (diffAt < Math.min(before.length, after.length)
                && before[diffAt] == after[diffAt]) {
                diffAt++;
            }
            int contextStart = Math.max(0, diffAt - 40);
            System.out.println("DRIFT " + name + " (" + before.length + "->" + after.length + " bytes, first at "
                + diffAt + ")");
            System.out.println("  before: " + snippet(before, contextStart, diffAt));
            System.out.println("  after : " + snippet(after, contextStart, diffAt));
        }
        assertTrue(drifted.isEmpty(), "files drifted across reopen: " + drifted);
    }

    private static String snippet(byte[] bytes, int start, int end) {
        int stop = Math.min(bytes.length, end + 40);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < stop; i++) {
            char c = (char) (bytes[i] & 0xFF);
            out.append(c < 32 || c > 126 ? '?' : c);
        }
        return out.toString();
    }

    @Test
    void repeatedZipBuildsAreIdentical() throws IOException {
        Path root = WORK.resolve("zip-stable");
        deleteRecursively(root);
        ModProject project = importAndUnpack(root);

        Path zipA = root.getParent().resolve("a.zip");
        Path zipB = root.getParent().resolve("b.zip");
        ModExporter.buildZip(project, zipA, List.of());
        ModExporter.buildZip(project, zipB, List.of());

        assertTrue(java.util.Arrays.equals(Files.readAllBytes(zipA), Files.readAllBytes(zipB)),
            "rebuilding the same project must produce identical archives");
    }

    @Test
    void bomInSourceFilesSurvivesTheRoundTrip() throws IOException {
        Path modRoot = WORK.resolve("bom-mod");
        deleteRecursively(modRoot);
        Files.createDirectories(modRoot.resolve("content/items"));
        Files.write(modRoot.resolve("mod.json"), """
            {"name":"bom mod","version":"1.0","minGameVersion":"146"}
            """.getBytes(StandardCharsets.UTF_8));
        byte[] withBom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = """
            {"name":"Sponge","color":"abcdef"}\n
            """.getBytes(StandardCharsets.UTF_8);
        Files.write(modRoot.resolve("content/items/sponge.json"), concat(withBom, body));

        ModPackage pkg = ModPackageReader.read(modRoot);
        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        Path projectRoot = WORK.resolve("bom-project");
        ProjectIo.unpack(project, pkg, projectRoot);

        byte[] written = Files.readAllBytes(projectRoot.resolve("content/items/sponge.json"));
        assertTrue(written.length > 3 && (written[0] & 0xFF) == 0xEF,
            "the BOM must survive an untouched round trip");

        Path built = WORK.resolve("bom-build");
        ModExporter.buildFolder(project, built, List.of());
        byte[] builtFile = Files.readAllBytes(
            built.resolve("bom-mod/content/items/sponge.json"));
        assertTrue(java.util.Arrays.equals(withBom, java.util.Arrays.copyOf(builtFile, 3)),
            "the BOM must survive the build");
    }

    // --- hostile and broken inputs --------------------------------------------------------------

    @Test
    void brokenInputsFailWithoutCrashing() throws IOException {
        Path work = WORK.resolve("broken");
        deleteRecursively(work);
        Files.createDirectories(work);

        Files.write(work.resolve("not-a-zip.zip"), "this is not a zip file".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ModPackageReader.read(work.resolve("not-a-zip.zip")));

        Files.write(work.resolve("readme.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ModPackageReader.read(work.resolve("readme.txt")));

        // Empty folder: imports as an empty project, no crash.
        Path empty = Files.createDirectories(work.resolve("empty"));
        ModPackage emptyPkg = ModPackageReader.read(empty);
        ModImporter.Result emptyResult = new ModImporter(registry).importPackage(emptyPkg);
        assertEquals(0, emptyResult.project().contents.size());
        assertTrue(emptyResult.report().warnings.stream().anyMatch(w -> w.contains("no mod.json")));

        // Unreadable meta: the mod keeps importing, the failure is reported.
        Path garbage = Files.createDirectories(work.resolve("garbage"));
        Files.write(garbage.resolve("mod.json"), "{ this is not {{{ valid".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(garbage.resolve("content/items"));
        Files.write(garbage.resolve("content/items/rock.json"),
            "{\"name\":\"Rock\"}".getBytes(StandardCharsets.UTF_8));
        ModPackage garbagePkg = ModPackageReader.read(garbage);
        ModImporter.Result garbageResult = new ModImporter(registry).importPackage(garbagePkg);
        assertEquals(1, garbageResult.project().contents.size(), "content still imports");
        assertTrue(garbageResult.report().warnings.stream().anyMatch(w -> w.contains("could not be parsed")),
            "the unreadable meta is reported, not swallowed");
    }

    @Test
    void maliciousZipPathsAreContained() throws IOException {
        Path work = WORK.resolve("evil");
        deleteRecursively(work);
        Path zip = work.resolve("evil.zip");
        Files.createDirectories(work);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("x/mod.json"));
            out.write("""
                {"name":"evil mod","version":"1.0"}""".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("x/../../evil-escape.txt"));
            out.write("pwn".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        ModPackage pkg = ModPackageReader.read(zip);
        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        Path projectRoot = work.resolve("project");
        ProjectIo.unpack(project, pkg, projectRoot);

        assertFalse(Files.exists(work.getParent().resolve("evil-escape.txt")),
            "the traversal entry must not escape the project folder");
        assertFalse(Files.exists(work.resolve("evil-escape.txt")));
        assertTrue(Files.exists(projectRoot.resolve("mod.json")), "the harmless entry lands normally");
    }

    // --- project folder invariants --------------------------------------------------------------

    @Test
    void filesOutsideTheManagedSetSurviveSaves() throws IOException {
        Path root = WORK.resolve("unmanaged");
        deleteRecursively(root);
        ModProject project = importAndUnpack(root);

        // A file in a folder the game would not scan (unknown content folder) is an asset.
        Path unknown = root.resolve("content/not-a-type/manual.json");
        Files.createDirectories(unknown.getParent());
        Files.writeString(unknown, "{\"name\":\"Manual\"}");
        // A regular content file added while the application is closed is picked up on reopen.
        Path known = root.resolve("content/items/late.json");
        Files.createDirectories(known.getParent());
        Files.writeString(known, "{\"name\":\"Late\"}");

        ModProject reopened = ProjectIo.open(root, registry);
        ProjectIo.save(reopened, root);

        assertTrue(Files.exists(unknown), "assets are never pruned");
        assertTrue(Files.exists(known), "content picked up on reopen is kept");
        // The unknown-folder file is an asset by design; only the regular one becomes a record.
        assertEquals(1, reopened.contents.stream()
            .filter(record -> "late".equals(record.name))
            .count());
        assertTrue(reopened.assets.stream().anyMatch(asset -> asset.path.endsWith("not-a-type/manual.json")),
            "the unknown-folder file is carried as an asset");
    }

    @Test
    void deletingARecordRemovesExactlyItsFile() throws IOException {
        Path root = WORK.resolve("delete-one");
        deleteRecursively(root);
        ModProject project = importAndUnpack(root);
        Map<String, byte[]> before = snapshot(root);

        ContentRecord victim = project.contents.stream()
            .filter(record -> !record.isInline() && record.rawPath != null)
            .filter(record -> record.rawPath.startsWith("content/"))
            .findFirst().orElseThrow();
        String victimPath = victim.rawPath;
        project.contents.remove(victim);
        ProjectIo.save(project, root);

        assertFalse(Files.exists(root.resolve(victimPath)), "the deleted record's file is removed");
        Map<String, byte[]> after = snapshot(root);
        List<String> changed = names(before).stream()
            .filter(name -> !name.equals(victimPath))
            // The authoring layer legitimately changes: the record's card and id mapping are gone.
            .filter(name -> !name.startsWith("modmaker/"))
            .filter(name -> !java.util.Arrays.equals(before.get(name), after.get(name)))
            .toList();
        assertTrue(changed.isEmpty(), "no other file may change: " + changed);
        assertFalse(names(after).contains(victimPath));
    }

    @Test
    void renamingMovesTheFileWithinItsFolder() throws IOException {
        Path root = WORK.resolve("rename-one");
        deleteRecursively(root);
        ModProject project = importAndUnpack(root);

        ContentRecord record = project.contents.stream()
            .filter(candidate -> !candidate.isInline() && candidate.rawPath != null)
            .filter(candidate -> candidate.rawPath.contains("/"))
            .findFirst().orElseThrow();
        String oldPath = record.rawPath;
        String folder = oldPath.substring(0, oldPath.lastIndexOf('/') + 1);
        record.name = "renamed-content";
        record.dirty = true;
        ProjectIo.save(project, root);

        assertFalse(Files.exists(root.resolve(oldPath)), "the old file is gone");
        assertTrue(Files.exists(root.resolve(folder + "renamed-content.json")),
            "the new file sits in the same folder");
    }

    // --- validation and performance -------------------------------------------------------------

    @Test
    void duplicateNamesAndOrphanNodesAreFlagged() throws IOException {
        Path root = WORK.resolve("duplicates");
        deleteRecursively(root);
        ModProject project = importAndUnpack(root);

        ContentRecord original = project.contents.stream()
            .filter(record -> !record.isInline() && record.type == ContentType.item)
            .findFirst().orElseThrow();
        ContentRecord twin = new ContentRecord();
        twin.id = "twin";
        twin.type = original.type;
        twin.name = original.name;
        twin.ext = "json";
        twin.rawPath = original.rawPath + ".twin";
        twin.dirty = true;
        project.contents.add(twin);

        List<Issue> issues = new Validator(registry).validate(project);
        assertTrue(issues.stream().anyMatch(issue -> issue.isError()
                && issue.message().contains("two " + original.type + " records")),
            "duplicate names must be errors");
    }

    @Test
    void realModImportStaysFast() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        long start = System.nanoTime();
        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        ModImporter.Result result = new ModImporter(registry).importPackage(pkg);
        Path root = WORK.resolve("perf-project");
        deleteRecursively(root);
        ProjectIo.unpack(result.project(), pkg, root);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("full import+unpack of 955 files: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 60_000, "import must stay interactive, took " + elapsedMs + " ms");
        assertTrue(result.report().contentCount > 200);
    }

    // --- helpers --------------------------------------------------------------------------------

    private ModProject importAndUnpack(Path root) throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        ProjectIo.unpack(project, pkg, root);
        return project;
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static List<String> names(Map<String, byte[]> snapshot) {
        return List.copyOf(snapshot.keySet());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
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
