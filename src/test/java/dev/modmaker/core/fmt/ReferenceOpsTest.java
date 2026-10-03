package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writing and undoing references - the model side of dragging a connection between cards. */
class ReferenceOpsTest {

    private static FieldDef field(FieldType type, String stackKind) {
        return new FieldDef("requirements", type, null, List.of(), "any", stackKind, null, true);
    }

    @Test
    void scalarReferenceIsWrittenAndReverted() {
        ContentRecord target = new ContentRecord();
        target.fields.put("requirements", new JsonPrimitive("old"));

        var revert = ReferenceOps.apply(target, field(FieldType.content, null), "steel-ingot");
        assertEquals("steel-ingot", target.fields.get("requirements").getAsString());

        ReferenceOps.revert(target, field(FieldType.content, null), revert);
        assertEquals("old", target.fields.get("requirements").getAsString());

        // A field that had no value before the connection is removed again on revert.
        ContentRecord fresh = new ContentRecord();
        var revertEmpty = ReferenceOps.apply(fresh, field(FieldType.content, null), "steel-ingot");
        ReferenceOps.revert(fresh, field(FieldType.content, null), revertEmpty);
        assertNull(fresh.fields.get("requirements"));
    }

    @Test
    void stackArrayGrowsByEntriesAndShrinksOnRevert() {
        ContentRecord target = new ContentRecord();

        var first = ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "copper");
        var second = ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "lead");
        JsonArray array = target.fields.get("requirements").getAsJsonArray();
        assertEquals(2, array.size());
        assertEquals("copper", array.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(1, array.get(0).getAsJsonObject().get("amount").getAsInt());
        assertEquals("lead", array.get(1).getAsJsonObject().get("item").getAsString());

        ReferenceOps.revert(target, field(FieldType.stackArray, "item"), second);
        assertEquals(1, target.fields.get("requirements").getAsJsonArray().size());
        ReferenceOps.revert(target, field(FieldType.stackArray, "item"), first);
        assertNull(target.fields.get("requirements"), "an emptied array is removed entirely");
    }

    @Test
    void singleStackReplacesAndRestoresAnyPreviousShape() {
        ContentRecord target = new ContentRecord();
        target.fields.put("requirements", new JsonPrimitive("copper/5"));

        ReferenceOps.apply(target, field(FieldType.stack, "item"), "lead");
        JsonObject entry = target.fields.get("requirements").getAsJsonObject();
        assertEquals("lead", entry.get("item").getAsString());

        // Best-effort removal matches by stack kind.
        assertTrue(ReferenceOps.remove(target, field(FieldType.stack, "item"), "lead"));
        assertNull(target.fields.get("requirements"));
    }

    @Test
    void removeMatchesByStackKindAndName() {
        ContentRecord target = new ContentRecord();
        ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "copper");
        ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "lead");

        assertFalse(ReferenceOps.remove(target, field(FieldType.stackArray, "item"), "not-there"));
        assertTrue(ReferenceOps.remove(target, field(FieldType.stackArray, "item"), "copper"));
        assertEquals(1, target.fields.get("requirements").getAsJsonArray().size());
    }
}
