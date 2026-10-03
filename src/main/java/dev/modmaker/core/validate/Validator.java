package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.ClassSchema;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a project against the rules the game loader actually enforces, plus mistakes that are silent
 * in game but wrong (missing sprites, dangling references, bundle keys with no content behind them).
 *
 * <p>Deliberately conservative: unknown JSON fields are only a warning in the game, so they are a
 * warning here; only things that make content fail to load are errors.
 */
public final class Validator {

    private final SchemaRegistry registry;

    public Validator(SchemaRegistry registry) {
        this.registry = registry;
    }

    public List<Issue> validate(ModProject project) {
        List<Issue> issues = new ArrayList<>();
        String internal = project.internalName();
        if (project.meta.name == null || project.meta.name.isBlank()) {
            issues.add(Issue.error("mod name is empty; every content name and bundle key depends on it"));
        }
        if (internal.isEmpty()) {
            return issues;
        }

        Map<String, ContentRecord> byName = new LinkedHashMap<>();
        Set<String> spriteRegions = SpriteRegions.regionsOf(project, internal);
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                // Nested content has no name of its own - it is just an object inside a host file,
                // so duplicate names and file-name rules do not apply to it.
                if (project.contentById(record.inlineOwnerId) == null) {
                    issues.add(Issue.error("inline " + record.type + " '" + record.name
                        + "' has no host record, so it would never be written", record.id));
                }
                continue;
            }
            String key = record.type.name() + ":" + record.name;
            if (byName.put(key, record) != null) {
                issues.add(Issue.error("two " + record.type + " records are named '" + record.name
                    + "'; the game throws on duplicate content names", record.id));
            }
            String problem = NameRules.contentNameProblem(record.name);
            if (problem != null) {
                issues.add(Issue.error(record.type + " '" + record.name + "': " + problem, record.id));
            }
            if (!record.type.fileBacked()) {
                issues.add(Issue.error(record.type + " '" + record.name + "' cannot be a file: the game has"
                    + " no parser for content/" + record.type.folderName(), record.id));
            }
            validateClass(record, issues);
            validateSprites(record, internal, spriteRegions, issues);
        }

        validateReferences(project, byName, issues);
        validateContentOrder(project, issues);
        validateStrings(project, issues);
        validateBoards(project, issues);
        return issues;
    }

    /** Cards whose content record no longer exists cannot be rendered or written. */
    private void validateBoards(ModProject project, List<Issue> issues) {
        for (var board : project.boards) {
            for (var node : board.nodes) {
                if (dev.modmaker.core.model.CanvasNode.KIND_CONTENT.equals(node.kind)
                    && node.contentId != null
                    && project.contentById(node.contentId) == null) {
                    issues.add(Issue.warning("board '" + board.name
                        + "' has a card whose content no longer exists; it will disappear on save"));
                }
            }
        }
    }

    private void validateClass(ContentRecord record, List<Issue> issues) {
        String declared = record.className();
        if (declared == null) {
            if (record.type.defaultClass().isEmpty()) {
                issues.add(Issue.error(record.type + " '" + record.name
                    + "' declares no \"type\"; the game requires one for " + record.type, record.id));
            }
            return;
        }
        String simple = declared.substring(declared.lastIndexOf('.') + 1);
        ClassSchema schema = registry.schema(simple);
        if (schema == null) {
            issues.add(Issue.warning(record.type + " '" + record.name + "' uses class '" + declared
                + "', which this schema generation does not know - its fields fall back to raw JSON",
                record.id));
            return;
        }
        if (record.patch && record.type == ContentType.block) {
            issues.add(Issue.warning("block '" + record.name + "' patches vanilla content but declares a"
                + " type, which makes it a new block instead", record.id));
        }
        if (!record.patch && schema.ctype != null && schema.ctype != record.type) {
            issues.add(Issue.warning(record.type + " '" + record.name + "' declares class '" + declared
                + "', which belongs to " + schema.ctype, record.id));
        }
    }

    /** Blocks and units need sprites; items, liquids and the rest do not. */
    private void validateSprites(ContentRecord record, String internal, Set<String> regions,
        List<Issue> issues) {
        if (!SpriteRegions.needsSprite(record)) {
            return;
        }
        String region = SpriteRegions.expectedRegion(record, internal);
        if (regions.isEmpty() || regions.contains(region)) {
            return;
        }
        issues.add(Issue.warning(record.type + " '" + record.name + "' has no sprite: expected atlas region '"
            + region + "', which needs sprites/" + record.name + ".png", record.id));
    }

    private void validateReferences(ModProject project, Map<String, ContentRecord> byName,
        List<Issue> issues) {
        String internal = project.internalName();
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                continue;
            }
            String className = registry.resolveClass(record.type, record.className());
            if (className == null) {
                continue;
            }
            for (FieldDef field : registry.fieldsOf(className)) {
                if (!field.isReference()) {
                    continue;
                }
                JsonElement value = record.fields.get(field.name());
                if (value == null) {
                    continue;
                }
                for (String name : collectNames(value, field)) {
                    if (!resolves(project, name, field, internal)) {
                        issues.add(Issue.warning(record.type + " '" + record.name + "' references '" + name
                            + "' in " + field.name()
                            + ", which is neither content of this mod nor known vanilla content", record.id));
                    }
                }
            }
        }
    }

    private boolean resolves(ModProject project, String name, FieldDef field, String internal) {
        String bare = name.startsWith(internal + "-") ? name.substring(internal.length() + 1) : name;
        for (ContentRecord candidate : project.contents) {
            if (candidate.isInline() || !candidate.name.equals(bare)) {
                continue;
            }
            if (field.contentCtype() == null || field.contentCtype().equals("any")
                || candidate.type.name().equals(field.contentCtype())) {
                return true;
            }
        }
        for (ContentType type : ContentType.values()) {
            if (field.contentCtype() != null && !field.contentCtype().equals("any")
                && !type.name().equals(field.contentCtype())) {
                continue;
            }
            if (registry.isVanillaName(type, bare)) {
                return true;
            }
        }
        return false;
    }

    private void validateContentOrder(ModProject project, List<Issue> issues) {
        Set<String> names = new LinkedHashSet<>();
        project.contents.stream().filter(record -> !record.isInline())
            .forEach(record -> names.add(record.name));
        for (String ordered : project.meta.contentOrder) {
            if (!names.contains(ordered)) {
                issues.add(Issue.warning("contentOrder lists '" + ordered
                    + "', which matches no content file name"));
            }
        }
    }

    private void validateStrings(ModProject project, List<Issue> issues) {
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : project.strings.values.entrySet()) {
            BundleCodec.ContentKey key = BundleCodec.parseKey(entry.getKey());
            if (key == null) {
                continue;
            }
            String bare = BundleCodec.contentNameOf(key.fullName(), project.internalName());
            if (project.contentByName(key.type(), bare) == null) {
                issues.add(Issue.warning("bundle key '" + entry.getKey()
                    + "' has no matching content file; the game will ignore it"));
            }
        }
    }

    /** Atlas regions the project's sprites actually produce. */
    private List<String> collectNames(JsonElement value, FieldDef field) {
        List<String> out = new ArrayList<>();
        collectNames(value, field, out);
        return out;
    }

    private void collectNames(JsonElement value, FieldDef field, List<String> out) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(element -> collectNames(element, field, out));
            return;
        }
        if (value.isJsonObject()) {
            for (String key : new String[] {"item", "liquid", "payload", "block", "unit", "content"}) {
                JsonElement named = value.getAsJsonObject().get(key);
                if (named != null && named.isJsonPrimitive() && named.getAsJsonPrimitive().isString()) {
                    out.add(named.getAsString());
                }
            }
            return;
        }
        if (!value.getAsJsonPrimitive().isString()) {
            return;
        }
        String text = value.getAsString().trim();
        if (text.isEmpty() || text.startsWith("icon-")) {
            return;
        }
        if (field.type().isStack()) {
            int slash = text.indexOf('/');
            if (slash > 0) {
                text = text.substring(0, slash);
            }
        }
        out.add(text);
    }
}
