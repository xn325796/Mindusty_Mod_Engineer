package dev.modmaker.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans a Mindustry source tree and writes the schema drafts the editor works from.
 *
 * <p>Outputs, both under schemas/:
 * <ul>
 *   <li>fields.json - every content-relevant class with its parent, content type and public
 *       instance fields, typed the same way the inspector needs them.</li>
 *   <li>vanilla-content.json - names of vanilla content per type, used to recognise patch files on
 *       import and to check references.</li>
 * </ul>
 *
 * <p>This is a regex draft: the curated schemas/schema.json always wins over it.
 */
public final class SchemaBootstrap {

    private static final String DEFAULT_SOURCE =
        "C:/Users/XN325/Desktop/mindustry/Mindustry-160.5/core/src/mindustry";

    /** Classes that root each content type; a subclass inherits the type transitively. */
    private static final Map<String, ContentType> ROOTS = Map.ofEntries(
        Map.entry("Item", ContentType.item),
        Map.entry("Liquid", ContentType.liquid),
        Map.entry("StatusEffect", ContentType.status),
        Map.entry("Block", ContentType.block),
        Map.entry("UnitType", ContentType.unit),
        Map.entry("BulletType", ContentType.bullet),
        Map.entry("Weather", ContentType.weather),
        Map.entry("SectorPreset", ContentType.sector),
        Map.entry("Planet", ContentType.planet),
        Map.entry("TeamEntry", ContentType.team),
        Map.entry("UnitCommand", ContentType.unitCommand),
        Map.entry("UnitStance", ContentType.unitStance));

    private static final Map<String, String> PRIMITIVES = Map.of(
        "boolean", "bool",
        "int", "number",
        "float", "number",
        "double", "number",
        "long", "number",
        "short", "number",
        "byte", "number",
        "String", "text",
        "Color", "color");

    private static final Pattern CLASS_DECLARATION = Pattern.compile(
        "\\bpublic\\s+(?:abstract\\s+|final\\s+)*class\\s+(\\w+)(?:<[^>]*>)?(?:\\s+extends\\s+([\\w.]+))?");
    private static final Pattern FIELD_DECLARATION = Pattern.compile(
        "^public\\s+(.+?)\\s+(\\w+)\\s*(?:=[^;]*)?;\\s*(?://.*)?$");
    private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(?:\\([^)]*\\))?");
    private static final Pattern CONTENT_CREATION = Pattern.compile(
        "new\\s+([A-Za-z_$][\\w.$]*)\\s*\\(\\s*\"([^\"]+)\"");
    /**
     * Ores are the one vanilla content whose name is derived rather than written out:
     * OreBlock(Item) delegates to {@code "ore-" + item.name} (OreBlock.java:26-28).
     */
    private static final Pattern ORE_CREATION = Pattern.compile(
        "new\\s+OreBlock\\s*\\(\\s*(?:[A-Za-z_$][\\w.$]*\\.)?(\\w+)\\s*\\)");

    private SchemaBootstrap() {
    }

    public static void main(String[] args) throws IOException {
        Path sourceRoot = Path.of(args.length > 0 ? args[0] : DEFAULT_SOURCE).toAbsolutePath().normalize();
        Path schemasDir = Path.of("schemas").toAbsolutePath().normalize();

        if (!Files.isDirectory(sourceRoot)) {
            System.err.println("source root not found: " + sourceRoot);
            System.exit(1);
        }

        List<Path> javaFiles = walkJava(sourceRoot);
        System.out.println("scanning " + javaFiles.size() + " java files in " + sourceRoot);

        Map<String, ClassInfo> classes = scanClasses(javaFiles);
        resolveTypes(classes);

        Map<ContentType, Set<String>> vanilla = scanVanilla(javaFiles, classes);

        writeFields(schemasDir.resolve("fields.json"), sourceRoot, classes);
        writeVanilla(schemasDir.resolve("vanilla-content.json"), sourceRoot, vanilla);

        Map<ContentType, Integer> perType = new TreeMap<>();
        int kept = 0;
        for (ClassInfo info : classes.values()) {
            if (info.ctype != null) {
                kept++;
                perType.merge(info.ctype, 1, Integer::sum);
            }
        }
        System.out.println("content classes=" + kept + " " + perType);
        for (Map.Entry<ContentType, Set<String>> entry : vanilla.entrySet()) {
            System.out.println("vanilla " + entry.getKey() + "=" + entry.getValue().size());
        }
    }

    // --- classes --------------------------------------------------------------------------------

    private static final class Field {
        final String name;
        final String javaType;

        Field(String name, String javaType) {
            this.name = name;
            this.javaType = javaType;
        }
    }

    private static final class ClassInfo {
        final String name;
        final String file;
        final String parent;
        final List<Field> fields;
        ContentType ctype;

        ClassInfo(String name, String file, String parent, List<Field> fields) {
            this.name = name;
            this.file = file;
            this.parent = parent;
            this.fields = fields;
        }
    }

    private static List<Path> walkJava(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static Map<String, ClassInfo> scanClasses(List<Path> files) throws IOException {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher declaration = CLASS_DECLARATION.matcher(source);
            if (!declaration.find()) {
                continue;
            }
            String name = declaration.group(1);
            String parent = declaration.group(2) == null
                ? null
                : declaration.group(2).substring(declaration.group(2).lastIndexOf('.') + 1);
            ClassInfo info = new ClassInfo(name, file.toString().replace('\\', '/'), parent,
                parseFields(source));
            info.ctype = ROOTS.get(name);
            classes.put(name, info);
        }
        return classes;
    }

    /** Fields the current model can express: public, non-static, non-final, no methods. */
    private static List<Field> parseFields(String source) {
        List<Field> fields = new ArrayList<>();
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("public ") || line.indexOf('(') >= 0
                || line.contains(" static ") || line.contains(" final ")) {
                continue;
            }
            String cleaned = ANNOTATION.matcher(line).replaceAll(" ").trim();
            if (cleaned.contains(" transient ")) {
                continue;
            }
            Matcher match = FIELD_DECLARATION.matcher(cleaned);
            if (!match.matches()) {
                continue;
            }
            String javaType = match.group(1).replace("final", "").trim();
            if (!javaType.matches("[\\w<>\\[\\],. ?]+")) {
                continue;
            }
            fields.add(new Field(match.group(2), javaType));
        }
        return fields;
    }

    /** Propagates the content type from a root class down through the extends chain. */
    private static void resolveTypes(Map<String, ClassInfo> classes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ClassInfo info : classes.values()) {
                if (info.ctype != null || info.parent == null) {
                    continue;
                }
                ClassInfo parent = classes.get(info.parent);
                if (parent != null && parent.ctype != null) {
                    info.ctype = parent.ctype;
                    changed = true;
                }
            }
        }
    }

    private static void writeFields(Path target, Path sourceRoot, Map<String, ClassInfo> classes)
        throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("generated", java.time.Instant.now().toString());
        root.addProperty("source", sourceRoot.toString().replace('\\', '/'));
        root.addProperty("note", "Draft from source scan. schemas/schema.json overrides these entries.");

        JsonObject out = new JsonObject();
        for (ClassInfo info : classes.values()) {
            if (info.ctype == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            if (info.parent != null) {
                entry.addProperty("extends", info.parent);
            }
            entry.addProperty("ctype", info.ctype.name());
            entry.addProperty("file", info.file);
            JsonArray fields = new JsonArray();
            for (Field field : info.fields) {
                JsonObject fieldJson = new JsonObject();
                fieldJson.addProperty("n", field.name);
                fieldJson.addProperty("t", draftType(field.javaType, classes));
                fields.add(fieldJson);
            }
            entry.add("fields", fields);
            out.add(info.name, entry);
        }
        root.add("classes", out);
        Json.writeFile(target, Json.write(root));
    }

    private static String draftType(String javaType, Map<String, ClassInfo> classes) {
        String base = javaType.replace("[]", "").trim();
        if (javaType.endsWith("[]")) {
            return switch (base) {
                case "ItemStack" -> "stack[]:item";
                case "LiquidStack" -> "stack[]:liquid";
                case "PayloadStack" -> "stack[]:payload";
                case "String" -> "text[]";
                default -> "raw";
            };
        }
        String primitive = PRIMITIVES.get(javaType);
        if (primitive != null) {
            return primitive;
        }
        if (ROOTS.containsKey(javaType)) {
            return "content";
        }
        ClassInfo info = classes.get(base);
        return info != null && info.ctype != null ? "content" : "raw";
    }

    // --- vanilla content names ------------------------------------------------------------------

    private static Map<ContentType, Set<String>> scanVanilla(
        List<Path> files, Map<String, ClassInfo> classes) throws IOException {
        Map<ContentType, Set<String>> names = new LinkedHashMap<>();
        for (Path file : files) {
            String path = file.toString().replace('\\', '/');
            if (!path.contains("/content/")) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = CONTENT_CREATION.matcher(source);
            while (matcher.find()) {
                String created = matcher.group(1);
                String simple = created.substring(created.lastIndexOf('.') + 1);
                ContentType type = ROOTS.get(simple);
                if (type == null) {
                    ClassInfo info = classes.get(simple);
                    type = info == null ? null : info.ctype;
                }
                if (type != null) {
                    names.computeIfAbsent(type, ignored -> new LinkedHashSet<>())
                        .add(matcher.group(2));
                }
            }
            // Derived ore names, e.g. new OreBlock(Items.titanium) becomes "ore-titanium".
            Matcher ores = ORE_CREATION.matcher(source);
            while (ores.find()) {
                names.computeIfAbsent(ContentType.block, ignored -> new LinkedHashSet<>())
                    .add("ore-" + ores.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static void writeVanilla(Path target, Path sourceRoot, Map<ContentType, Set<String>> vanilla)
        throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("generated", java.time.Instant.now().toString());
        root.addProperty("source", sourceRoot.toString().replace('\\', '/'));
        root.addProperty("note",
            "Vanilla content names, used to recognise patch files and to check references. "
                + "Names built at runtime from variables are not listed.");
        JsonObject types = new JsonObject();
        for (ContentType type : ContentType.values()) {
            JsonArray names = new JsonArray();
            for (String name : vanilla.getOrDefault(type, Set.of()).stream().sorted().toList()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
            if (!names.isEmpty()) {
                types.add(type.name(), names);
            }
        }
        root.add("content", types);
        Json.writeFile(target, Json.write(root));
    }
}
