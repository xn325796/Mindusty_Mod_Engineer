package dev.modmaker.core.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;

import java.util.LinkedHashMap;

/**
 * One content definition: a single content/&lt;folder&gt;/&lt;name&gt;.json|hjson file.
 *
 * <p>Content is stored as a parsed field tree plus, when it came from an existing mod, the exact
 * original text. Untouched records are written back byte for byte, so comments, formatting and
 * custom keys survive; only edited records are re-serialised.
 */
public final class ContentRecord {
    public String id;
    public ContentType type = ContentType.item;
    /** File base name. The content's registered name is {@code <internalModName>-<name>}. */
    public String name = "";
    /** "json" or "hjson"; the game reads both. */
    public String ext = "json";
    /** True when this overrides existing content instead of creating new content. */
    public boolean patch;

    public LinkedHashMap<String, JsonElement> fields = new LinkedHashMap<>();

    /** Original file text, or null for content created in this project. */
    public String raw;
    /** Location inside the mod when imported, e.g. "content/blocks/工厂/DOV.json". */
    public String rawPath;

    /**
     * Set when this record is a nested object inside another record's field tree rather than its own
     * file - a bullet in a turret's ammoTypes, for instance. Such records produce no file of their
     * own; they are written back into the owner at {@link #inlinePath}.
     */
    public String inlineOwnerId;
    public String inlinePath;

    /** Set when the field tree no longer matches {@link #raw}. */
    public boolean dirty;

    public boolean isInline() {
        return inlineOwnerId != null;
    }

    /**
     * Deterministic ordering used wherever record order leaks into output (auto layout, the authoring
     * file map): file-backed records first by path, nested records after by position in the host.
     */
    public static final java.util.Comparator<ContentRecord> DETERMINISTIC_ORDER =
        java.util.Comparator.comparing((ContentRecord record) -> record.isInline())
            .thenComparing(record -> record.isInline() || record.rawPath == null ? "" : record.rawPath)
            .thenComparing(record -> record.isInline() ? record.inlinePath == null ? "" : record.inlinePath : "")
            .thenComparing(record -> record.name);

    public ContentRecord() {
    }

    public static ContentRecord create(String id, ContentType type, String name) {
        ContentRecord record = new ContentRecord();
        record.id = id;
        record.type = type;
        record.name = name;
        record.dirty = true;
        return record;
    }

    /** The "type" field, i.e. the Java class that defines this content, or null when absent. */
    public String className() {
        JsonElement value = fields.get("type");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.isJsonPrimitive() ? value.getAsString() : null;
    }

    public void setClassName(String className) {
        if (className == null || className.isEmpty()) {
            fields.remove("type");
        } else {
            fields.put("type", new JsonPrimitive(className));
        }
        dirty = true;
    }

    public String fullName(String internalModName) {
        return NameRules.fullName(internalModName, name);
    }

    /**
     * The field tree as a real JSON object, so nested edits (a bullet inside ammoTypes) can be made
     * with path helpers and then written back through {@link #applyTree}.
     */
    public com.google.gson.JsonObject tree() {
        com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        fields.forEach(object::add);
        return object;
    }

    public void applyTree(com.google.gson.JsonObject tree) {
        fields.clear();
        tree.entrySet().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
    }

    /** Folder for this record inside a mod package, preserving any nested subfolder it came from. */
    public String folder() {
        return type.folderName();
    }
}
