package dev.modmaker.core.io;

import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Authoring from scratch: a new project has to come out as a complete, loadable package, and content
 * nested inside another file (a bullet in a turret's ammoTypes) has to be editable and land back in
 * its host file.
 */
class AuthoringTest {

    private static final Path MOD_ROOT =
        dev.modmaker.core.io.TestFixtures.realModRoot();
    private static final Path WORK = Paths.get("build", "authoring").toAbsolutePath();

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    @Test
    void aNewProjectBuildsACompleteLoadablePackage() throws IOException {
        Path root = WORK.resolve("new-project");
        deleteRecursively(root);

        ModProject project = ProjectFactory.create("My Mod", root);

        ContentRecord item = ContentRecord.create("c1", ContentType.item, "steel-ingot");
        item.fields.put("color", new JsonPrimitive("#8899aa"));
        item.fields.put("hardness", new JsonPrimitive(3));
        project.contents.add(item);
        project.strings.put(NameRules.bundleKey(ContentType.item, "my-mod-steel-ingot", "name"),
            "en", "Steel Ingot");
        project.strings.markBundleDirty("en");

        ContentRecord block = ContentRecord.create("c2", ContentType.block, "steel-smelter");
        block.setClassName("GenericCrafter");
        block.fields.put("craftTime", new JsonPrimitive(60));
        block.fields.put("requirements", dev.modmaker.core.json.Json.parse("[{item: copper, amount: 30}]"));
        block.fields.put("research", new JsonPrimitive("steel-ingot"));
        project.contents.add(block);
        project.strings.put(NameRules.bundleKey(ContentType.block, "my-mod-steel-smelter", "name"),
            "en", "Steel Smelter");
        project.strings.markBundleDirty("en");

        AutoLayout.apply(project, project.boards.get(0));
        ProjectIo.save(project, root);

        // The folder on disk has everything a mod needs.
        assertTrue(Files.exists(root.resolve("mod.hjson")));
        assertTrue(Files.exists(root.resolve("content/items/steel-ingot.json")));
        assertTrue(Files.exists(root.resolve("content/blocks/steel-smelter.json")));
        assertTrue(Files.exists(root.resolve("bundles/bundle.properties")));

        String meta = Files.readString(root.resolve("mod.hjson"));
        assertTrue(meta.contains("\"name\": \"My Mod\""), meta);
        assertTrue(meta.contains("\"minGameVersion\""), meta);

        String itemJson = Files.readString(root.resolve("content/items/steel-ingot.json"));
        assertTrue(itemJson.contains("hardness"), itemJson);
        String blockJson = Files.readString(root.resolve("content/blocks/steel-smelter.json"));
        assertTrue(blockJson.contains("GenericCrafter"), "a non-default class must be written out");
        assertTrue(blockJson.contains("steel-ingot"), "the tech tree parent is written as declared");

        String bundle = Files.readString(root.resolve("bundles/bundle.properties"));
        assertTrue(bundle.contains("item.my-mod-steel-ingot.name=Steel Ingot"), bundle);
        assertEquals(BundleCodec.fileNameOf("en"), "bundle.properties");

        // Reading it back the way the game would, and validating, finds nothing wrong.
        ModPackage reopened = ModPackageReader.read(root);
        assertEquals(2, reopened.contents.size());
        assertEquals("My Mod", reopened.meta.name);
        assertEquals(List.of(), new Validator(registry).validate(project).stream()
            .filter(issue -> issue.isError()).toList());

        // And it builds.
        ExportReport report = ModExporter.buildFolder(project, WORK.resolve("new-build"), List.of());
        assertEquals(4, report.files().size(), "meta + two content files + bundle");
        assertTrue(Files.exists(WORK.resolve("new-build/my-mod/mod.hjson")));
    }

    @Test
    void editingAnInlineBulletLandsInItsHostFile() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        Path root = WORK.resolve("inline-project");
        deleteRecursively(root);

        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        ModImporter.Result imported = new ModImporter(registry).importPackage(pkg);
        ModProject project = imported.project();
        ProjectIo.unpack(project, pkg, root);

        ContentRecord bullet = project.contents.stream()
            .filter(ContentRecord::isInline)
            .filter(record -> record.fields.containsKey("damage"))
            .findFirst().orElseThrow();
        ContentRecord owner = project.contentById(bullet.inlineOwnerId);
        assertNotNull(owner);
        String ownerPath = owner.rawPath;
        assertFalse(owner.dirty, "the host starts clean");

        bullet.fields.put("damage", new JsonPrimitive(99));
        bullet.dirty = true;

        ProjectIo.save(project, root);

        String hostText = Files.readString(root.resolve(ownerPath));
        assertTrue(hostText.contains("99"), "the bullet's new value must be written into " + ownerPath);
        assertFalse(bullet.dirty, "saving clears the inline record's dirty flag");
        assertTrue(Files.exists(root.resolve(ownerPath)));

        // Only file-backed records produce files: the project's content tree must hold exactly one
        // file per non-inline record, and nothing for the bullet.
        long fileBacked = project.contents.stream().filter(record -> !record.isInline()).count();
        try (Stream<Path> walk = Files.walk(root.resolve("content"))) {
            long contentFiles = walk.filter(Files::isRegularFile)
                .filter(file -> {
                    String name = file.getFileName().toString().toLowerCase();
                    return name.endsWith(".json") || name.endsWith(".hjson");
                })
                .count();
            assertEquals(fileBacked, contentFiles,
                "one file per file-backed record, none for nested content");
        }
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
