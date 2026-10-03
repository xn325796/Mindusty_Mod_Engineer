package dev.modmaker.core.schema;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * One editable field of a content class.
 *
 * @param name         field name as it appears in the content JSON
 * @param type         widget kind
 * @param description  tooltip text from the curated schema, or null
 * @param enumValues   allowed values when {@code type} is enumeration
 * @param contentCtype content type the value may reference ("any" when unconstrained)
 * @param stackKind    item | liquid | payload for stack fields
 * @param defaultValue default from the curated schema, or null
 * @param curated      true when hand-written rather than drafted from a source scan
 */
public record FieldDef(
    String name,
    FieldType type,
    String description,
    List<String> enumValues,
    String contentCtype,
    String stackKind,
    JsonElement defaultValue,
    boolean curated
) {
    public boolean isReference() {
        return type.isReference();
    }
}
