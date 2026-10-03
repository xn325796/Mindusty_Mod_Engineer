package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;

/**
 * Writing and undoing cross-content references inside a record's field tree - the model side of
 * dragging a connection between two cards.
 *
 * <p>Reference values are written as the bare source content name: the game resolves bare names
 * against the current mod first (ContentParser's find/locate helpers), and vanilla targets must not
 * be prefixed anyway. Stack fields become {"item":name,"amount":1} objects, the shape vanilla JSON
 * uses.
 */
public final class ReferenceOps {

    /** Everything needed to undo one {@link #apply}. */
    public record Revert(boolean appended, int appendedIndex, JsonElement previous) {
    }

    private ReferenceOps() {
    }

    public static Revert apply(ContentRecord target, FieldDef field, String sourceName) {
        if (field.type() == FieldType.stackArray) {
            JsonElement previous = target.fields.get(field.name());
            JsonArray array = previous != null && previous.isJsonArray()
                ? previous.getAsJsonArray()
                : new JsonArray();
            int index = array.size();
            array.add(stackEntry(field.stackKind(), sourceName));
            target.fields.put(field.name(), array);
            return new Revert(true, index, previous);
        }
        JsonElement previous = target.fields.get(field.name());
        if (field.type() == FieldType.stack) {
            target.fields.put(field.name(), stackEntry(field.stackKind(), sourceName));
        } else {
            target.fields.put(field.name(), new JsonPrimitive(sourceName));
        }
        return new Revert(false, -1, previous);
    }

    public static void revert(ContentRecord target, FieldDef field, Revert revert) {
        if (revert.appended()) {
            JsonElement current = target.fields.get(field.name());
            if (current != null && current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                if (revert.appendedIndex() < array.size()) {
                    array.remove(revert.appendedIndex());
                }
                if (array.isEmpty()) {
                    target.fields.remove(field.name());
                }
            }
            return;
        }
        if (revert.previous() == null) {
            target.fields.remove(field.name());
        } else {
            target.fields.put(field.name(), revert.previous());
        }
    }

    /** Best-effort removal when no revert info exists (an edge loaded from disk, for instance). */
    public static boolean remove(ContentRecord target, FieldDef field, String sourceName) {
        JsonElement current = target.fields.get(field.name());
        if (current == null) {
            return false;
        }
        if (current.isJsonArray()) {
            JsonArray array = current.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (matches(array.get(i), field.stackKind(), sourceName)) {
                    array.remove(i);
                    if (array.isEmpty()) {
                        target.fields.remove(field.name());
                    }
                    return true;
                }
            }
            return false;
        }
        if (matches(current, field.stackKind(), sourceName)) {
            target.fields.remove(field.name());
            return true;
        }
        return false;
    }

    private static JsonObject stackEntry(String stackKind, String sourceName) {
        JsonObject entry = new JsonObject();
        entry.addProperty(stackKind == null || stackKind.isEmpty() ? "item" : stackKind, sourceName);
        entry.addProperty("amount", 1);
        return entry;
    }

    private static boolean matches(JsonElement element, String stackKind, String sourceName) {
        if (element.isJsonPrimitive()) {
            return element.getAsString().equals(sourceName);
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonElement named = element.getAsJsonObject()
            .get(stackKind == null || stackKind.isEmpty() ? "item" : stackKind);
        return named != null && named.isJsonPrimitive() && named.getAsString().equals(sourceName);
    }
}
