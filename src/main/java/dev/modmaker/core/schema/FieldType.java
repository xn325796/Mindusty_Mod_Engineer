package dev.modmaker.core.schema;

/** Field widget kinds, matching the "type" strings used by schemas/schema.json and fields.json. */
public enum FieldType {
    number,
    text,
    textArray,
    bool,
    color,
    /** One value out of a fixed list (schema spelling: "enum"). */
    enumeration,
    /** A reference to another content object, by name. */
    content,
    /** A sprite/region name. */
    sprite,
    /** A single item/liquid/payload stack, written as "name/amount". */
    stack,
    /** A list of stacks (schema spelling: "stack[]"). */
    stackArray,
    /** Anything else: edited as raw JSON. */
    raw;

    public static FieldType parse(String rawType) {
        if (rawType == null) {
            return raw;
        }
        String value = rawType.trim();
        if (value.startsWith("stack[]")) {
            return stackArray;
        }
        return switch (value) {
            case "stack" -> stack;
            case "text[]" -> textArray;
            case "enum" -> enumeration;
            case "number" -> number;
            case "text" -> text;
            case "bool" -> bool;
            case "color" -> color;
            case "content" -> content;
            case "sprite" -> sprite;
            default -> raw;
        };
    }

    /** Fields whose value names other content; these become edges on the canvas. */
    public boolean isReference() {
        return this == content || this == stack || this == stackArray;
    }

    public boolean isStack() {
        return this == stack || this == stackArray;
    }
}
