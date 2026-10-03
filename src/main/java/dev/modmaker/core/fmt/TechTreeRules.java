package dev.modmaker.core.fmt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reading and writing of the {@code research} field, which is the only way mod content enters the
 * tech tree (there are no tech tree files; ContentParser.java:1337-1418 builds nodes from content).
 *
 * <p>A plain string means "parent content name". An object may carry parent, requirements,
 * objectives, planet and root.
 */
public final class TechTreeRules {

    public static final String FIELD = "research";

    private TechTreeRules() {
    }

    /** The parent content name, or null when this is a root node or the value is unusable. */
    public static String parent(JsonElement research) {
        if (research == null || research.isJsonNull()) {
            return null;
        }
        if (research.isJsonPrimitive()) {
            String parent = research.getAsString();
            return parent.isBlank() ? null : parent;
        }
        if (research.isJsonObject()) {
            JsonObject object = research.getAsJsonObject();
            if (isRoot(object)) {
                return null;
            }
            JsonElement parent = object.get("parent");
            if (parent != null && parent.isJsonPrimitive()) {
                String name = parent.getAsString();
                return name.isBlank() ? null : name;
            }
        }
        return null;
    }

    /** ContentParser.java:1394 — research nodes with root:true become their own tech tree. */
    public static boolean isRoot(JsonElement research) {
        return research != null && research.isJsonObject() && isRoot(research.getAsJsonObject());
    }

    private static boolean isRoot(JsonObject research) {
        JsonElement root = research.get("root");
        return root != null && root.isJsonPrimitive() && root.getAsBoolean();
    }

    /** Whether the value is shaped like something the game will accept as a research node. */
    public static boolean isWellFormed(JsonElement research) {
        if (!isRoot(research) && parent(research) == null) {
            // A non-root node without a parent is dropped by the game with a warning.
            return false;
        }
        return true;
    }
}
