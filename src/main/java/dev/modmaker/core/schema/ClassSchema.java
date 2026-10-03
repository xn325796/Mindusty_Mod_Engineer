package dev.modmaker.core.schema;

import dev.modmaker.core.fmt.ContentType;

import java.util.LinkedHashMap;

/** Fields known for one content class, plus where it sits in the class hierarchy. */
public final class ClassSchema {
    public String name;
    /** Parent used for field merging; the curated schema may override it. */
    public String parent;
    /**
     * Parent from the Java source scan, never overridden by the curated schema. Field-merging
     * shortcuts (e.g. Battery extending Block directly) must not distort hierarchy questions such
     * as "which classes are in the same branch".
     */
    public String hierarchyParent;
    public ContentType ctype;
    /** True for the class that roots a content type (Item, Block, ...). */
    public boolean base;
    /** True when schemas/schema.json describes this class by hand. */
    public boolean curated;
    public LinkedHashMap<String, FieldDef> fields = new LinkedHashMap<>();

    public ClassSchema(String name) {
        this.name = name;
    }

    /** The parent that reflects the real Java class hierarchy. */
    public String trueParent() {
        return hierarchyParent != null ? hierarchyParent : parent;
    }
}
