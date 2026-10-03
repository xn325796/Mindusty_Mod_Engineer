package dev.modmaker.core.fmt;

import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprite bookkeeping shared by the validator and the sprite panel: which atlas regions a project's
 * sprite assets produce, and which content records are missing theirs.
 */
public final class SpriteRegions {

    private SpriteRegions() {
    }

    /**
     * Whether the game expects a sprite for this record: blocks, units and statuses need one;
     * patches edit existing content (sprite already there) and nested content is not a file.
     */
    public static boolean needsSprite(ContentRecord record) {
        return switch (record.type) {
            case block, unit, status -> true;
            default -> false;
        } && !record.patch && !record.isInline();
    }

    /** Atlas regions the project's sprite assets produce (Mods.packSprites naming rules). */
    public static Set<String> regionsOf(ModProject project, String internalName) {
        Set<String> regions = new LinkedHashSet<>();
        if (internalName == null || internalName.isEmpty()) {
            return regions;
        }
        for (AssetRef asset : project.assets) {
            boolean override = asset.path.startsWith("sprites-override/");
            if (!override && !asset.path.startsWith("sprites/")) {
                continue;
            }
            String fileName = asset.path.substring(asset.path.lastIndexOf('/') + 1);
            int dot = fileName.lastIndexOf('.');
            if (dot < 0 || !fileName.substring(dot + 1).equals("png")) {
                continue;
            }
            regions.add(NameRules.spriteRegion(internalName, fileName.substring(0, dot), !override));
        }
        return regions;
    }

    /** The atlas region a record is expected to fill, e.g. {@code my-mod-kiln}. */
    public static String expectedRegion(ContentRecord record, String internalName) {
        return NameRules.spriteRegion(internalName, record.name, true);
    }

    /** Records that need a sprite but the project's assets do not provide one. */
    public static List<ContentRecord> withoutSprite(ModProject project, String internalName) {
        List<ContentRecord> out = new ArrayList<>();
        Set<String> regions = regionsOf(project, internalName);
        if (regions.isEmpty()) {
            return out;
        }
        for (ContentRecord record : project.contents) {
            if (needsSprite(record) && !regions.contains(expectedRegion(record, internalName))) {
                out.add(record);
            }
        }
        return out;
    }
}
