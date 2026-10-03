package dev.modmaker.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ModMeta;

import java.util.Map;

/** mod.json / mod.hjson reading and writing, mirroring Mods.ModMeta (Mods.java:1392-1416). */
public final class ModMetaCodec {

    private ModMetaCodec() {
    }

    public static ModMeta fromJson(JsonObject object) {
        ModMeta meta = new ModMeta();
        meta.name = string(object, "name", "");
        meta.displayName = string(object, "displayName", meta.name);
        meta.author = string(object, "author", "");
        meta.description = string(object, "description", "");
        meta.subtitle = string(object, "subtitle", "");
        meta.version = string(object, "version", "1.0");
        meta.minGameVersion = string(object, "minGameVersion", "146");
        meta.dependencies = strings(object, "dependencies");
        meta.softDependencies = strings(object, "softDependencies");
        meta.hidden = bool(object, "hidden", false);
        meta.repo = string(object, "repo", "");
        meta.texturescale = number(object, "texturescale", 1);
        meta.contentOrder = strings(object, "contentOrder");
        meta.mainClass = string(object, "main", "");
        meta.java = bool(object, "java", false);
        meta.kotlin = bool(object, "kotlin", false);

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!KNOWN.contains(entry.getKey())) {
                meta.extra.put(entry.getKey(), entry.getValue());
            }
        }
        return meta;
    }

    /**
     * Writes the meta back. Only meaningful keys are emitted, but {@code extra} is kept so unknown
     * fields a mod declared survive a round trip.
     */
    public static JsonObject toJson(ModMeta meta) {
        JsonObject object = new JsonObject();
        object.addProperty("name", meta.name);
        if (notBlank(meta.displayName) && !meta.displayName.equals(meta.name)) {
            object.addProperty("displayName", meta.displayName);
        }
        if (notBlank(meta.author)) {
            object.addProperty("author", meta.author);
        }
        if (notBlank(meta.description)) {
            object.addProperty("description", meta.description);
        }
        if (notBlank(meta.subtitle)) {
            object.addProperty("subtitle", meta.subtitle);
        }
        if (notBlank(meta.version)) {
            object.addProperty("version", meta.version);
        }
        if (notBlank(meta.minGameVersion)) {
            object.addProperty("minGameVersion", meta.minGameVersion);
        }
        if (notBlank(meta.repo)) {
            object.addProperty("repo", meta.repo);
        }
        if (meta.hidden) {
            object.addProperty("hidden", true);
        }
        if (meta.texturescale != 1) {
            object.addProperty("texturescale", meta.texturescale);
        }
        if (!meta.dependencies.isEmpty()) {
            object.add("dependencies", array(meta.dependencies));
        }
        if (!meta.softDependencies.isEmpty()) {
            object.add("softDependencies", array(meta.softDependencies));
        }
        if (!meta.contentOrder.isEmpty()) {
            object.add("contentOrder", array(meta.contentOrder));
        }
        if (notBlank(meta.mainClass)) {
            object.addProperty("main", meta.mainClass);
        }
        if (meta.java) {
            object.addProperty("java", true);
        }
        if (meta.kotlin) {
            object.addProperty("kotlin", true);
        }
        for (Map.Entry<String, JsonElement> entry : meta.extra.entrySet()) {
            object.add(entry.getKey(), entry.getValue());
        }
        return object;
    }

    private static final java.util.Set<String> KNOWN = java.util.Set.of(
        "name", "displayName", "author", "description", "subtitle", "version", "minGameVersion",
        "dependencies", "softDependencies", "hidden", "repo", "texturescale", "contentOrder",
        "main", "java", "kotlin");

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsBoolean();
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsDouble();
    }

    private static java.util.List<String> strings(JsonObject object, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JsonElement value = object.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    out.add(element.getAsString());
                }
            }
        }
        return out;
    }

    private static JsonArray array(java.util.List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(value -> array.add(new JsonPrimitive(value)));
        return array;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
