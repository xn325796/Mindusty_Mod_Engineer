package dev.modmaker.core.fmt;

import com.google.gson.JsonObject;
import dev.modmaker.core.json.HjsonReader;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The format rules that decide how names, folders, sprite regions and bundles are shaped. */
class FmtRulesTest {

    @Test
    void internalNameFollowsModMetaCleanup() {
        assertEquals("greg-industry", NameRules.internalName("Greg Industry"));
        assertEquals("社会主义工业化", NameRules.internalName("社会主义工业化"));
        assertEquals("", NameRules.internalName(null));
    }

    @Test
    void contentNamesArePrefixedWithTheInternalName() {
        assertEquals("my-mod-kiln", NameRules.fullName("my-mod", "kiln"));
        assertTrue(NameRules.isValidContentName("kiln-2"));
        assertFalse(NameRules.isValidContentName("kiln 2"));
    }

    @Test
    void spriteRegionsMatchTheGamesPrefixingRule() {
        // sprites/kiln.png -> my-mod-kiln
        assertEquals("my-mod-kiln", NameRules.spriteRegion("my-mod", "kiln", true));
        // Quirk of Mods.java:404-412: the "already prefixed" test only matches category prefixes,
        // so a file that starts with the mod name itself gets prefixed twice.
        assertEquals("my-mod-my-mod-kiln", NameRules.spriteRegion("my-mod", "my-mod-kiln", true));
        // category prefix wins over the mod prefix (the `block-my-mod-x-full` case)
        assertEquals("block-my-mod-kiln-full", NameRules.spriteRegion("my-mod", "block-my-mod-kiln-full", true));
        // sprites-override/ is never prefixed
        assertEquals("ui-button", NameRules.spriteRegion("my-mod", "ui-button", false));
        // dots are kept in the packed name; only the lookup check strips them
        assertEquals("my-mod-kiln.alt", NameRules.spriteRegion("my-mod", "kiln.alt", true));
        assertEquals("kiln", NameRules.regionLookupName("kiln.alt"));
    }

    @Test
    void onlyNineContentTypesCanBeDeclaredInFiles() {
        assertEquals(9, ContentType.creatable().size());
        assertTrue(ContentType.item.fileBacked());
        assertFalse(ContentType.bullet.fileBacked(), "bullets have no content parser and must stay inline");
        assertFalse(ContentType.unitCommand.fileBacked());
    }

    @Test
    void folderAliasesCoverTheLegacyPluralNames() {
        assertEquals(Set.of("status", "statuses"), ContentType.status.acceptedFolders());
        assertEquals(Set.of("weathers", "weather"), ContentType.weather.acceptedFolders());
        assertEquals(Set.of("items"), ContentType.item.acceptedFolders());
        assertEquals(ContentType.status, ContentType.fromFolder("status"));
        assertEquals(ContentType.weather, ContentType.fromFolder("weathers"));
        assertEquals(ContentType.unitStance, ContentType.fromFolder("unitStances"));
        assertNull(ContentType.fromFolder("nonsense"));
    }

    @Test
    void bundleFilesAndLocales() {
        assertTrue(BundleCodec.isBundleFile("bundle.properties"));
        assertTrue(BundleCodec.isBundleFile("bundle_zh_CN.properties"));
        // The dash-wrapped name seen in real mods does not start with "bundle" and is ignored by the game.
        assertFalse(BundleCodec.isBundleFile("---bundle.properties---"));
        assertEquals("", BundleCodec.localeOf("bundle.properties"));
        assertEquals("zh_CN", BundleCodec.localeOf("bundle_zh_CN.properties"));
        assertEquals("bundle_zh_CN.properties", BundleCodec.fileNameOf("zh_CN"));
        assertEquals("bundle.properties", BundleCodec.fileNameOf(""));
        // English is the game's base bundle, not bundle_en.properties.
        assertEquals("bundle.properties", BundleCodec.fileNameOf("en"));
    }

    @Test
    void parsesManagedBundleKeysOnly() {
        BundleCodec.ContentKey key = BundleCodec.parseKey("block.my-mod-kiln.name");
        assertNotNull(key);
        assertEquals(ContentType.block, key.type());
        assertEquals("my-mod-kiln", key.fullName());
        assertEquals("name", key.field());
        assertEquals("kiln", BundleCodec.contentNameOf(key.fullName(), "my-mod"));

        assertNull(BundleCodec.parseKey("category.条件"));
        assertNull(BundleCodec.parseKey("stat.条件"));
        assertNull(BundleCodec.parseKey("mod.my-mod.name"));
        assertNull(BundleCodec.parseKey("block.my-mod-kiln.tooltip"));
        // Bullets are not file backed, so their keys stay in the extra table.
        assertNull(BundleCodec.parseKey("bullet.my-mod-shell.name"));
    }

    @Test
    void readsResearchValues() {
        JsonObject tree = HjsonReader.readObject("""
            { "a": "copper", "b": { "parent": "my-mod-root", "requirements": [] },
              "c": { "root": true, "name": "techtree.x" }, "d": {} }
            """);
        assertEquals("copper", TechTreeRules.parent(tree.get("a")));
        assertEquals("my-mod-root", TechTreeRules.parent(tree.get("b")));
        assertTrue(TechTreeRules.isRoot(tree.get("c")));
        assertNull(TechTreeRules.parent(tree.get("c")));
        assertTrue(TechTreeRules.isWellFormed(tree.get("a")));
        assertFalse(TechTreeRules.isWellFormed(tree.get("d")), "a node without parent or root is dropped by the game");
    }

    @Test
    void propertiesRoundTripRealWorldShapes() {
        String text = """
            # a comment
            block.rtg-generator.name = RTG
            item.pyratite.name=硫
            long.value = one \\
              two
            semi\\:colon = value\\:with\\:colons
            """;
        PropertiesFile file = PropertiesFile.parse(text);
        assertEquals("RTG", file.values.get("block.rtg-generator.name"));
        assertEquals("硫", file.values.get("item.pyratite.name"));
        assertEquals("one two", file.values.get("long.value"));
        assertEquals("value:with:colons", file.values.get("semi:colon"));

        PropertiesFile reparsed = PropertiesFile.parse(file.write());
        assertEquals(file.values, reparsed.values);
    }
}
