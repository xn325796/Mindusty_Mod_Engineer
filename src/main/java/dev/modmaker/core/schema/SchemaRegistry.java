package dev.modmaker.core.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Field knowledge for every content class, merged from two layers:
 * schemas/schema.json (curated by hand, wins per field) and schemas/fields.json (drafted by
 * {@code schemaBootstrap} from the Mindustry sources, fills the gaps). schemas/vanilla-content.json
 * supplies vanilla names for patch detection and reference checks.
 */
public final class SchemaRegistry {

    private final Map<String, ClassSchema> classes = new LinkedHashMap<>();
    private final Map<ContentType, Set<String>> vanilla = new LinkedHashMap<>();

    private SchemaRegistry() {
    }

    public static SchemaRegistry load(Path schemasDir) throws IOException {
        SchemaRegistry registry = new SchemaRegistry();
        Path generated = schemasDir.resolve("fields.json");
        if (Files.exists(generated)) {
            registry.loadGenerated(Json.parseObject(Json.readFile(generated)));
        }
        Path curated = schemasDir.resolve("schema.json");
        if (Files.exists(curated)) {
            registry.loadCurated(Json.parseObject(Json.readFile(curated)));
        }
        Path vanillaFile = schemasDir.resolve("vanilla-content.json");
        if (Files.exists(vanillaFile)) {
            registry.loadVanilla(Json.parseObject(Json.readFile(vanillaFile)));
        }
        return registry;
    }

    /**
     * Finds the schemas directory the way the application needs it: an explicit system property,
     * the working directory, its parent, or finally the copy packaged into the jar.
     */
    public static SchemaRegistry loadDefault() throws IOException {
        String override = System.getProperty("modmaker.schemas");
        List<Path> candidates = new ArrayList<>();
        if (override != null) {
            candidates.add(Path.of(override));
        }
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        candidates.add(working.resolve("schemas"));
        if (working.getParent() != null) {
            candidates.add(working.getParent().resolve("schemas"));
        }
        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("fields.json")) || Files.exists(candidate.resolve("schema.json"))) {
                return load(candidate);
            }
        }

        SchemaRegistry registry = new SchemaRegistry();
        registry.loadGenerated(readResource("/schemas/fields.json"));
        registry.loadCurated(readResource("/schemas/schema.json"));
        registry.loadVanilla(readResource("/schemas/vanilla-content.json"));
        return registry;
    }

    private static JsonObject readResource(String name) throws IOException {
        try (InputStream stream = SchemaRegistry.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("schema resource missing: " + name);
            }
            return Json.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // --- loading --------------------------------------------------------------------------------

    private void loadGenerated(JsonObject root) {
        JsonObject classesJson = root.getAsJsonObject("classes");
        if (classesJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : classesJson.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            ClassSchema schema = classes.computeIfAbsent(entry.getKey(), ClassSchema::new);
            if (schema.hierarchyParent == null && schema.parent == null
                && object.has("extends") && !object.get("extends").isJsonNull()) {
                schema.parent = object.get("extends").getAsString();
            }
            if (schema.hierarchyParent == null && object.has("extends")
                && !object.get("extends").isJsonNull()) {
                schema.hierarchyParent = object.get("extends").getAsString();
            }
            if (schema.ctype == null && object.has("ctype")) {
                schema.ctype = ContentType.valueOf(object.get("ctype").getAsString());
            }
            if (object.has("fields")) {
                for (JsonElement fieldElement : object.getAsJsonArray("fields")) {
                    JsonObject fieldJson = fieldElement.getAsJsonObject();
                    String name = fieldJson.get("n").getAsString();
                    String rawType = fieldJson.get("t").getAsString();
                    FieldType type = FieldType.parse(rawType);
                    schema.fields.putIfAbsent(name, new FieldDef(name, type, null, List.of(),
                        type == FieldType.content ? "any" : null, stackKindOf(rawType), null, false));
                }
            }
        }
        for (ClassSchema schema : classes.values()) {
            if (schema.ctype != null && schema.name.equals(schema.ctype.defaultClass())) {
                schema.base = true;
            }
        }
    }

    private void loadCurated(JsonObject root) {
        JsonObject classesJson = root.getAsJsonObject("classes");
        if (classesJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : classesJson.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            ClassSchema schema = classes.computeIfAbsent(entry.getKey(), ClassSchema::new);
            schema.curated = true;
            if (object.has("ctype")) {
                schema.ctype = ContentType.valueOf(object.get("ctype").getAsString());
            }
            if (object.has("base")) {
                schema.base = object.get("base").getAsBoolean();
            }
            if (object.has("extends") && !object.get("extends").isJsonNull()) {
                schema.parent = object.get("extends").getAsString();
            }
            JsonObject fields = object.getAsJsonObject("fields");
            if (fields == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> fieldEntry : fields.entrySet()) {
                JsonObject fieldJson = fieldEntry.getValue().getAsJsonObject();
                String rawType = fieldJson.has("type") ? fieldJson.get("type").getAsString() : "raw";
                FieldType type = FieldType.parse(rawType);
                List<String> enumValues = new ArrayList<>();
                if (fieldJson.has("enumValues")) {
                    for (JsonElement value : fieldJson.getAsJsonArray("enumValues")) {
                        enumValues.add(value.getAsString());
                    }
                }
                schema.fields.put(fieldEntry.getKey(), new FieldDef(
                    fieldEntry.getKey(),
                    type,
                    fieldJson.has("desc") ? fieldJson.get("desc").getAsString() : null,
                    enumValues,
                    fieldJson.has("contentCtype") ? fieldJson.get("contentCtype").getAsString()
                        : type == FieldType.content ? "any" : null,
                    fieldJson.has("stackKind") ? fieldJson.get("stackKind").getAsString() : stackKindOf(rawType),
                    fieldJson.has("default") ? fieldJson.get("default") : null,
                    true));
            }
        }
    }

    private void loadVanilla(JsonObject root) {
        JsonObject content = root.getAsJsonObject("content");
        if (content == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : content.entrySet()) {
            Set<String> names = new LinkedHashSet<>();
            for (JsonElement name : entry.getValue().getAsJsonArray()) {
                names.add(name.getAsString());
            }
            vanilla.put(ContentType.valueOf(entry.getKey()), names);
        }
    }

    private static String stackKindOf(String rawType) {
        int colon = rawType.indexOf(':');
        return colon < 0 ? null : rawType.substring(colon + 1);
    }

    // --- queries --------------------------------------------------------------------------------

    public ClassSchema schema(String className) {
        return classes.get(className);
    }

    public Set<String> classNames() {
        return classes.keySet();
    }

    public boolean isCurated(String className) {
        ClassSchema schema = classes.get(className);
        return schema != null && schema.curated;
    }

    /** Fields of a class, walking the extends chain so base fields come first. */
    public List<FieldDef> fieldsOf(String className) {
        LinkedHashMap<String, FieldDef> merged = new LinkedHashMap<>();
        collect(className, merged, new LinkedHashSet<>());
        return new ArrayList<>(merged.values());
    }

    private void collect(String className, LinkedHashMap<String, FieldDef> merged, Set<String> seen) {
        ClassSchema schema = classes.get(className);
        if (schema == null || !seen.add(className)) {
            return;
        }
        if (schema.parent != null) {
            collect(schema.parent, merged, seen);
        }
        merged.putAll(schema.fields);
    }

    /** Classes belonging to a content type, base class first, then alphabetically. */
    public List<String> classesForType(ContentType type) {
        String base = baseClassOf(type);
        List<String> names = new ArrayList<>();
        for (ClassSchema schema : classes.values()) {
            if (schema.ctype == type) {
                names.add(schema.name);
            }
        }
        names.sort(Comparator.comparing((String name) -> !name.equals(base))
            .thenComparing(Comparator.naturalOrder()));
        return names;
    }

    /** Ancestor chain of a class: the class itself, then each parent up to the hierarchy root. */
    private List<String> ancestorChain(String className) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = className;
        while (current != null && seen.add(current)) {
            chain.add(current);
            ClassSchema schema = classes.get(current);
            current = schema == null ? null : schema.trueParent();
        }
        return chain;
    }

    /**
     * Classes the user may switch to from {@code current} without leaving the same branch of the
     * class hierarchy: the current class's ancestor chain (including the type base, so a record can
     * always go back to being generic), plus every class whose ancestry meets the current class
     * below the type base. A battery can become a power node or a plain block, but not an item
     * bridge. When {@code current} is the base class itself there is nothing to narrow by, so the
     * full class list is offered.
     */
    public List<String> relatedClasses(ContentType type, String current) {
        String root = baseClassOf(type);
        if (current == null || current.isBlank() || current.equals(root)) {
            return classesForType(type);
        }
        // Truncate at the type root: ancestors above it (UnlockableContent, Content) are shared by
        // every class and would otherwise make the whole type look "related".
        List<String> chain = new ArrayList<>();
        for (String ancestor : ancestorChain(current)) {
            chain.add(ancestor);
            if (ancestor.equals(root)) {
                break;
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>(chain);
        for (ClassSchema schema : classes.values()) {
            if (schema.ctype != type || out.contains(schema.name)) {
                continue;
            }
            if (meetsBelowRoot(ancestorChain(schema.name), chain, root)) {
                out.add(schema.name);
            }
        }
        return new ArrayList<>(out);
    }

    /** True when the candidate's ancestry meets the current chain anywhere above the type root. */
    private boolean meetsBelowRoot(List<String> candidateChain, List<String> currentChain, String root) {
        Set<String> chainSet = new LinkedHashSet<>(currentChain);
        for (String ancestor : candidateChain) {
            if (chainSet.contains(ancestor)) {
                // The chains are linear: meeting the root first means different branches.
                return !ancestor.equals(root);
            }
        }
        return false;
    }

    /** The class the game instantiates when a content file omits "type". */
    public String baseClassOf(ContentType type) {
        for (ClassSchema schema : classes.values()) {
            if (schema.base && schema.ctype == type) {
                return schema.name;
            }
        }
        String fallback = type.defaultClass();
        return fallback.isEmpty() ? null : fallback;
    }

    /**
     * Resolves the class for a content record. Null means the type has to be declared explicitly:
     * weather is the one type whose parser reads "type" with no default, so a missing type is a hard
     * error there (ContentParser.java:909-918).
     */
    public String resolveClass(ContentType type, String declaredType) {
        if (declaredType != null && !declaredType.isBlank()) {
            return declaredType;
        }
        String fallback = type.defaultClass();
        return fallback.isEmpty() ? null : fallback;
    }

    public Set<String> vanillaNames(ContentType type) {
        return vanilla.getOrDefault(type, Set.of());
    }

    public boolean isVanillaName(ContentType type, String name) {
        return vanillaNames(type).contains(name.toLowerCase(java.util.Locale.ROOT));
    }
}
