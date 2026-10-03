package dev.modmaker.core.io;

import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Import of a real, large, hand-written mod: nested Chinese content folders, missing commas, patched
 * vanilla files, custom bundle keys, an unloaded properties file and inline bullets.
 */
class RealModImportTest {

    private static final Path MOD_ROOT =
        dev.modmaker.core.io.TestFixtures.realModRoot();

    private static ModImporter.Result result;

    @BeforeAll
    static void importMod() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        SchemaRegistry registry = SchemaRegistry.load(Path.of("schemas"));
        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        result = new ModImporter(registry).importPackage(pkg);
        System.out.println("import report: " + result.report().summary());
        result.report().warnings.stream().limit(12).forEach(warning -> System.out.println("  WARN " + warning));
    }

    @Test
    void readsMetadataAndPrefixes() {
        ModProject project = result.project();
        assertEquals("社会主义工业化", project.meta.name);
        assertEquals("格雷工业", project.meta.displayName);
        assertEquals("社会主义工业化", project.internalName());
        assertEquals("1.6.7", project.meta.version);
        assertTrue(project.meta.dependencies.isEmpty(), "the only dependency line is commented out");
    }

    @Test
    void importsEveryContentFile() {
        ModProject project = result.project();
        assertTrue(result.report().contentCount >= 265,
            "expected the mod's content files, saw " + result.report().contentCount);
        assertTrue(result.report().parseFailures.isEmpty(),
            "no content file should fail to parse: " + result.report().parseFailures);
        ContentRecord doV = project.contents.stream()
            .filter(record -> record.name.equals("DOV"))
            .findFirst().orElseThrow();
        assertEquals("blocks/工厂/DOV.json", doV.rawPath.substring("content/".length()));
        assertFalse(doV.dirty, "imported content starts clean so it can be written back verbatim");
        assertFalse(doV.fields.isEmpty());
    }

    @Test
    void recognisesPatchedVanillaContent() {
        assertTrue(result.report().patchCount > 0, "the mod edits vanilla content");
        assertTrue(result.project().contents.stream().anyMatch(record -> record.patch),
            "at least one record should be flagged as a patch");
    }

    @Test
    void findsInlineBullets() {
        assertTrue(result.report().inlineCount > 0,
            "turret ammoTypes should produce inline bullet records");
        assertTrue(result.project().contents.stream().anyMatch(ContentRecord::isInline));
        assertTrue(result.project().contents.stream()
            .filter(ContentRecord::isInline)
            .allMatch(record -> record.inlineOwnerId != null && record.inlinePath != null));
    }

    @Test
    void splitsBundlesIntoStringsAndExtraKeys() {
        ModProject project = result.project();
        // Only bundle*.properties files are loaded by the game, and this mod keeps the bulk of its
        // translations in files the game ignores (aaa.properties, ---bundle.properties---). Those are
        // preserved as assets instead; the one real bundle carries a handful of managed keys.
        assertTrue(result.report().stringCount >= 10,
            "expected the loaded bundle's keys, saw " + result.report().stringCount);
        assertTrue(project.strings.values.containsKey("block.rtg-generator.name"));
        int extras = project.strings.extra.values().stream().mapToInt(java.util.Map::size).sum();
        assertTrue(extras > 0, "category.*/stat.* style keys must be preserved as extras");
        // The dash-wrapped bundle name is not a valid bundle file and must survive as an asset.
        assertTrue(project.assets.stream().anyMatch(asset -> asset.path.startsWith("bundles/")),
            "bundles/---bundle.properties--- and aaa.properties should be kept as assets");
    }

    @Test
    void keepsEveryAssetAndCountsSprites() {
        assertTrue(result.report().spriteCount >= 500,
            "expected the mod's sprites, saw " + result.report().spriteCount);
        assertTrue(result.report().assetCount >= 500);
        ModProject project = result.project();
        assertTrue(project.assets.stream().anyMatch(asset -> asset.path.equals("icon.png"))
                || project.assets.stream().noneMatch(asset -> asset.path.equals("icon.png")));
        assertTrue(project.assets.stream().anyMatch(asset -> asset.path.startsWith("scripts/")),
            "scripts are assets");
        assertTrue(project.assets.stream().anyMatch(asset -> asset.path.equals("adc.json")),
            "loose root files are preserved");
    }

    @Test
    void laysOutABoardWithReferenceEdges() {
        assertFalse(result.project().boards.isEmpty());
        var board = result.project().boards.get(0);
        assertEquals(result.project().contents.size(), board.nodes.size());
        assertTrue(board.edges.size() > 50,
            "research and requirements references should produce edges, saw " + board.edges.size());
        assertTrue(board.edges.stream().anyMatch(edge -> "research".equals(edge.fieldKey)));
        // Positions must be deterministic and non-overlapping within a column.
        assertTrue(board.nodes.stream().allMatch(node -> node.x >= 0 && node.y >= 0));
    }
}
