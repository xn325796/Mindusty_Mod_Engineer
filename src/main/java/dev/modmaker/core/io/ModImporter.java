package dev.modmaker.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Ids;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.PropertiesFile;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.json.JsonPath;
import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.ClassSchema;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a parsed {@link ModPackage} into an editable {@link ModProject}: content records keep their
 * original text, nested objects that the game can only express inline (bullets) become their own
 * nodes, bundles are split into the string table plus untouched extra keys, every other file is
 * registered as an asset, and the imported content is laid out on a board with reference edges.
 *
 * <p>Nothing is dropped: unrecognised files, unknown fields and custom bundle keys all survive, which
 * is what makes an untouched import export byte for byte.
 */
public final class ModImporter {

    private final SchemaRegistry registry;

    public ModImporter(SchemaRegistry registry) {
        this.registry = registry;
    }

    public record Result(ModProject project, ImportReport report) {
    }

    public Result importPackage(ModPackage pkg) {
        ModProject project = new ModProject();
        ImportReport report = new ImportReport();
        report.source = pkg.sourceLabel;
        project.meta = pkg.meta;
        // Carry the meta provenance so saving an untouched project rewrites the original bytes,
        // comments and all - not a re-serialised version of the parsed metadata.
        project.metaFile = pkg.metaFile;
        project.metaText = pkg.metaText;
        project.metaDirty = false;
        project.importedFrom = pkg.sourceLabel;

        String internal = project.internalName();
        if (pkg.metaFile.isEmpty()) {
            report.warn("no mod.json / mod.hjson found - metadata starts empty");
        } else if (!pkg.metaError.isEmpty()) {
            report.warn(pkg.metaFile + " could not be parsed and was kept verbatim: " + pkg.metaError);
        } else if (internal.isEmpty()) {
            report.warn("mod name is empty in " + pkg.metaFile + " - content prefixes cannot be resolved");
        }
        if (pkg.fromZip && !pkg.rootPrefix.isEmpty()) {
            report.warn("zip contained a single wrapping folder '" + pkg.rootPrefix
                + "' - unwrapped the same way the game does");
        }

        importContents(pkg, project, report, internal);
        importInline(project, report);
        // Deterministic order keeps the layout, the authoring file map and diffs stable across
        // imports of the same package, regardless of filesystem walk order.
        project.contents.sort(ContentRecord.DETERMINISTIC_ORDER);
        importStrings(pkg, project, report);
        importAssets(pkg, project, report);
        buildBoard(project, report, internal);

        report.skipped.addAll(pkg.skipped);
        return new Result(project, report);
    }

    // --- content --------------------------------------------------------------------------------

    private void importContents(ModPackage pkg, ModProject project, ImportReport report, String internal) {
        for (ModPackage.SourceContent source : pkg.contents) {
            ContentRecord record = new ContentRecord();
            record.id = Ids.uid("c");
            record.type = source.type;
            record.name = source.name;
            record.ext = source.ext;
            record.raw = source.text;
            record.rawPath = source.path;
            record.fields.putAll(source.fields);

            if (source.parseError != null) {
                report.parseFailures.put(source.path, source.parseError);
                report.warn("could not parse " + source.path + " (kept verbatim): " + source.parseError);
            }
            if (isPatch(source, report)) {
                record.patch = true;
                report.patchCount++;
            }

            String folder = folderOf(source.path);
            if (folder != null && !source.type.acceptedFolders().contains(folder)) {
                report.warn("content folder '" + folder + "' is not the name the game looks for "
                    + source.type.acceptedFolders() + "; it will not load on a case-sensitive filesystem");
            }
            if (!NameRules.isValidContentName(record.name)) {
                report.warn(record.name + ": " + NameRules.contentNameProblem(record.name));
            }
            if (record.className() == null && source.type.defaultClass().isEmpty()) {
                report.warn(source.path + " declares no \"type\"; the game requires one for "
                    + source.type);
            }
            project.contents.add(record);
            report.contentCount++;
        }
    }

    /**
     * Mirrors ContentParser's patch detection: a file whose name matches existing content modifies
     * that content instead of creating new content. Only vanilla names are knowable here - another
     * mod's content would need that mod loaded. Blocks are special: declaring "type" makes it a new
     * block even when the name collides (ContentParser.java:583-592).
     */
    private boolean isPatch(ModPackage.SourceContent source, ImportReport report) {
        if (!registry.isVanillaName(source.type, source.name)) {
            return false;
        }
        if (source.type == ContentType.block && source.fields.containsKey("type")) {
            report.warn("content/blocks/" + source.name + ".json declares a type over the vanilla block '"
                + source.name + "'; the game treats it as a NEW block (omit \"type\" to patch it)");
            return false;
        }
        return true;
    }

    private static String folderOf(String contentPath) {
        int start = contentPath.indexOf('/') + 1;
        int end = contentPath.indexOf('/', start);
        return end < 0 ? null : contentPath.substring(start, end);
    }

    // --- inline content -------------------------------------------------------------------------

    /**
     * A bullet defined inside a turret's ammoTypes is not a file: the game has no parser for
     * content/bullets. Such objects become their own records pointing back at the host, so they can
     * be edited as nodes while the host file stays the single place they are written to.
     */
    private void importInline(ModProject project, ImportReport report) {
        List<ContentRecord> inline = new ArrayList<>();
        for (ContentRecord owner : new ArrayList<>(project.contents)) {
            if (owner.isInline() || owner.fields.isEmpty()) {
                continue;
            }
            JsonObject view = new JsonObject();
            owner.fields.forEach(view::add);
            for (Map.Entry<String, JsonElement> field : view.entrySet()) {
                walkInline(owner, field.getValue(), field.getKey(), inline, report, 0);
            }
        }
        project.contents.addAll(inline);
        report.inlineCount = inline.size();
    }

    private void walkInline(ContentRecord owner, JsonElement node, String path, List<ContentRecord> out,
        ImportReport report, int depth) {
        if (node == null || node.isJsonNull() || depth > 6) {
            return;
        }
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            ContentType inlineType = inlineTypeOf(object);
            if (inlineType != null) {
                ContentRecord record = new ContentRecord();
                record.id = Ids.uid("i");
                record.type = inlineType;
                record.name = JsonPath.leafName(path);
                record.inlineOwnerId = owner.id;
                record.inlinePath = path;
                object.entrySet().forEach(entry -> record.fields.put(entry.getKey(), entry.getValue()));
                record.dirty = false;
                out.add(record);
                return;
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                walkInline(owner, entry.getValue(), JsonPath.child(path, entry.getKey()), out, report,
                    depth + 1);
            }
        } else if (node.isJsonArray()) {
            JsonArray array = node.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                walkInline(owner, array.get(i), JsonPath.childIndex(path, i), out, report, depth + 1);
            }
        }
    }

    /** The content type of a nested object's "type" value, when that type has no file parser. */
    private ContentType inlineTypeOf(JsonObject object) {
        JsonElement typeField = object.get("type");
        if (typeField == null || !typeField.isJsonPrimitive()) {
            return null;
        }
        String className = typeField.getAsString();
        int dot = className.lastIndexOf('.');
        if (dot >= 0) {
            className = className.substring(dot + 1);
        }
        ClassSchema schema = registry.schema(className);
        if (schema == null || schema.ctype == null || schema.ctype.fileBacked()) {
            return null;
        }
        return schema.ctype;
    }

    // --- strings --------------------------------------------------------------------------------

    private void importStrings(ModPackage pkg, ModProject project, ImportReport report) {
        Set<String> locales = new LinkedHashSet<>();
        for (Map.Entry<String, String> bundle : pkg.bundleFiles.entrySet()) {
            String fileName = bundle.getKey().substring(bundle.getKey().lastIndexOf('/') + 1);
            String locale = BundleCodec.localeOf(fileName);
            // bundle.properties is the base bundle; the game uses it as the fallback, which is what
            // this tool calls "en".
            if (locale.isEmpty()) {
                locale = "en";
            }
            locales.add(locale);
            project.strings.bundleFiles.put(locale, fileName);

            PropertiesFile properties = PropertiesFile.parse(bundle.getValue());
            for (Map.Entry<String, String> entry : properties.values.entrySet()) {
                BundleCodec.ContentKey key = BundleCodec.parseKey(entry.getKey());
                if (key != null) {
                    project.strings.put(entry.getKey(), locale, entry.getValue());
                    report.stringCount++;
                } else {
                    project.strings.extra
                        .computeIfAbsent(locale, ignored -> new LinkedHashMap<>())
                        .put(entry.getKey(), entry.getValue());
                }
            }
            // Keep the original text so an untouched bundle is written back byte for byte.
            project.strings.bundleRaw.put(locale, bundle.getValue());
            project.strings.bundleFiles.put(locale, fileName);
            project.strings.bundleDirty.put(locale, false);
            report.bundleCount++;
        }
        List<String> ordered = new ArrayList<>();
        ordered.add("en");
        locales.stream().filter(locale -> !locale.equals("en")).sorted().forEach(ordered::add);
        project.locales = ordered;
    }

    // --- assets ---------------------------------------------------------------------------------

    private void importAssets(ModPackage pkg, ModProject project, ImportReport report) {
        for (Map.Entry<String, byte[]> file : pkg.assetFiles.entrySet()) {
            String path = file.getKey();
            project.assets.add(new AssetRef(path, file.getValue().length, assetKind(path)));
            report.assetCount++;
            if (assetKind(path).startsWith("sprite")) {
                report.spriteCount++;
                // Mods.java:168-169 packs only files whose extension is exactly "png".
                if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    report.warn(path + " is in a sprite folder but its extension is not lowercase"
                        + " \".png\" - the game will not pack it");
                }
            }
        }
    }

    static String assetKind(String path) {
        if (path.startsWith("sprites/")) {
            return "sprite";
        }
        if (path.startsWith("sprites-override/")) {
            return "spriteOverride";
        }
        if (path.startsWith("scripts/")) {
            return "script";
        }
        if (path.startsWith("sounds/")) {
            return "sound";
        }
        if (path.startsWith("music/")) {
            return "music";
        }
        if (path.startsWith("maps/")) {
            return "map";
        }
        if (path.startsWith("schematics/")) {
            return "schematic";
        }
        if (path.equals("icon.png") || path.equals("preview.png")) {
            return "icon";
        }
        return "extra";
    }

    // --- board ----------------------------------------------------------------------------------

    private void buildBoard(ModProject project, ImportReport report, String internal) {
        Board board = new Board();
        board.id = Ids.uid("b");
        board.name = "Main";
        board.file = "main.canvas";
        project.boards.add(board);

        Map<String, String> nodeByContent = AutoLayout.apply(project, board);
        Map<String, Set<String>> seen = new LinkedHashMap<>();

        for (ContentRecord record : project.contents) {
            String fromNode = nodeByContent.get(record.id);
            if (fromNode == null) {
                continue;
            }
            if (record.isInline()) {
                String ownerNode = nodeByContent.get(record.inlineOwnerId);
                if (ownerNode != null) {
                    addEdge(board, seen, ownerNode, fromNode, firstSegment(record.inlinePath),
                        JsonPath.leafName(record.inlinePath));
                    report.edgeCount++;
                }
                continue;
            }

            String className = registry.resolveClass(record.type, record.className());
            if (className != null) {
                for (FieldDef field : registry.fieldsOf(className)) {
                    if (!field.isReference()) {
                        continue;
                    }
                    JsonElement value = record.fields.get(field.name());
                    if (value == null) {
                        continue;
                    }
                    for (String name : referenceNames(value, field)) {
                        if (linkTo(project, board, seen, nodeByContent, record, name, field, internal)) {
                            report.edgeCount++;
                        }
                    }
                }
            }

            // research is the only way mod content reaches the tech tree; schema types it loosely,
            // so it is handled explicitly.
            String parent = TechTreeRules.parent(record.fields.get(TechTreeRules.FIELD));
            if (parent != null && linkTo(project, board, seen, nodeByContent, record, parent, null, internal)) {
                report.edgeCount++;
            }
        }
    }

    private boolean linkTo(ModProject project, Board board, Map<String, Set<String>> seen,
        Map<String, String> nodeByContent, ContentRecord from, String targetName, FieldDef field,
        String internal) {
        ContentRecord target = resolveTarget(project, targetName, field, internal);
        if (target == null) {
            return false;
        }
        String fromNode = nodeByContent.get(from.id);
        String toNode = nodeByContent.get(target.id);
        if (fromNode == null || toNode == null || fromNode.equals(toNode)) {
            return false;
        }
        String fieldKey = field == null ? TechTreeRules.FIELD : field.name();
        return addEdge(board, seen, fromNode, toNode, fieldKey, targetName);
    }

    private boolean addEdge(Board board, Map<String, Set<String>> seen, String from, String to,
        String fieldKey, String label) {
        String signature = from + "->" + to + "#" + fieldKey;
        if (!seen.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(signature)) {
            return false;
        }
        CanvasEdge edge = new CanvasEdge();
        edge.id = Ids.uid("e");
        edge.from = from;
        edge.to = to;
        edge.fieldKey = fieldKey;
        edge.label = label;
        board.edges.add(edge);
        return true;
    }

    /** Names a reference field points at, tolerating the shapes real mods use. */
    private List<String> referenceNames(JsonElement value, FieldDef field) {
        List<String> names = new ArrayList<>();
        collectNames(value, field, names);
        return names;
    }

    private void collectNames(JsonElement value, FieldDef field, List<String> out) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                collectNames(element, field, out);
            }
            return;
        }
        if (value.isJsonPrimitive()) {
            if (!value.getAsJsonPrimitive().isString()) {
                return;
            }
            String text = value.getAsString().trim();
            if (text.isEmpty() || text.startsWith("icon-")) {
                return;
            }
            if (field != null && field.type().isStack()) {
                int slash = text.indexOf('/');
                if (slash > 0) {
                    text = text.substring(0, slash);
                }
            }
            out.add(text);
            return;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : new String[] {"item", "liquid", "payload", "block", "unit", "content"}) {
                JsonElement named = object.get(key);
                if (named != null && named.isJsonPrimitive() && named.getAsJsonPrimitive().isString()) {
                    out.add(named.getAsString());
                }
            }
        }
    }

    private ContentRecord resolveTarget(ModProject project, String name, FieldDef field, String internal) {
        String bare = name;
        String prefix = internal + "-";
        if (!internal.isEmpty() && bare.startsWith(prefix)) {
            bare = bare.substring(prefix.length());
        }
        for (ContentRecord record : project.contents) {
            if (record.isInline() || !record.name.equals(bare)) {
                continue;
            }
            if (field != null && field.contentCtype() != null && !field.contentCtype().equals("any")
                && !record.type.name().equals(field.contentCtype())) {
                continue;
            }
            return record;
        }
        return null;
    }

    private static String firstSegment(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }
}
