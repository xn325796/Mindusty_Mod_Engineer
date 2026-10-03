package dev.modmaker.core.fmt;

import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprite bookkeeping shared by the validator and the sprite tab. */
class SpriteRegionsTest {

    private static ContentRecord record(ContentType type, String name) {
        ContentRecord record = new ContentRecord();
        record.id = "id-" + name;
        record.type = type;
        record.name = name;
        return record;
    }

    @Test
    void regionsFollowThePackingRules() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        project.assets.add(new AssetRef("sprites/kiln.png", 1, "sprite"));
        project.assets.add(new AssetRef("sprites/deep/nested/logo.png", 1, "sprite"));
        project.assets.add(new AssetRef("sprites-override/ui-icon.png", 1, "spriteOverride"));
        project.assets.add(new AssetRef("sprites/ignore.txt", 1, "sprite"));
        project.assets.add(new AssetRef("scripts/main.js", 1, "script"));

        var regions = SpriteRegions.regionsOf(project, "my-mod");
        assertTrue(regions.contains("my-mod-kiln"));
        assertTrue(regions.contains("my-mod-logo"), "nested sprites are scanned recursively");
        assertTrue(regions.contains("ui-icon"), "override sprites are never prefixed");
        assertEquals(3, regions.size(), "non-png and non-sprite files are skipped");
    }

    @Test
    void missingSpritesOnlyCoverBlocksUnitsAndStatuses() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        project.contents.add(record(ContentType.block, "steel-smelter"));
        project.contents.add(record(ContentType.unit, "walker"));
        project.contents.add(record(ContentType.status, "corroded"));
        project.contents.add(record(ContentType.item, "steel-ingot"));
        // The missing-sprite check only means anything once the project has sprite assets at all;
        // a data-only mod legitimately ships none.
        project.assets.add(new AssetRef("sprites/unrelated.png", 1, "sprite"));

        List<ContentRecord> missing = SpriteRegions.withoutSprite(project, "my-mod");
        assertEquals(3, missing.size(), "items need no sprite: " + missing);

        // Providing one sprite clears exactly that record.
        project.assets.add(new AssetRef("sprites/steel-smelter.png", 1, "sprite"));
        missing = SpriteRegions.withoutSprite(project, "my-mod");
        assertEquals(2, missing.size());
        assertTrue(missing.stream().noneMatch(record -> record.name.equals("steel-smelter")));
    }

    @Test
    void aProjectWithoutAnySpriteAssetsHasNoMissingList() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        project.contents.add(record(ContentType.block, "steel-smelter"));
        assertTrue(SpriteRegions.regionsOf(project, "my-mod").isEmpty());
        assertTrue(SpriteRegions.withoutSprite(project, "my-mod").isEmpty(),
            "no assets means no expectations, matching the validator");
    }

    @Test
    void patchesAndInlineContentNeverNeedSprites() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ContentRecord patch = record(ContentType.block, "duct");
        patch.patch = true;
        project.contents.add(patch);
        ContentRecord owner = record(ContentType.block, "turret");
        project.contents.add(owner);
        ContentRecord inline = record(ContentType.bullet, "shell");
        inline.inlineOwnerId = owner.id;
        inline.inlinePath = "ammoTypes.shell";
        project.contents.add(inline);

        assertTrue(SpriteRegions.withoutSprite(project, "my-mod").isEmpty());
        assertFalse(SpriteRegions.needsSprite(patch));
        assertFalse(SpriteRegions.needsSprite(inline));
        assertTrue(SpriteRegions.needsSprite(record(ContentType.block, "plain")));
    }
}
