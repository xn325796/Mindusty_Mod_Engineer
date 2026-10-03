package dev.modmaker.core.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole editable mod: metadata, content records, string table, boards and assets.
 *
 * <p>This is the single source of truth both the importer and the exporter work against, so the
 * canvas never has to know about mod package layout.
 */
public final class ModProject {
    public static final int FORMAT = 1;

    public int format = FORMAT;
    public ModMeta meta = new ModMeta();
    /** Name of the meta file as found on disk (mod.json, plugin.hjson, ...). */
    public String metaFile = "";
    /** Original meta text, written back verbatim while the metadata is untouched. */
    public String metaText = "";
    public boolean metaDirty;
    /** Content locales, "en" first. Drives which bundle files are written. */
    public List<String> locales = new ArrayList<>(List.of("en"));
    public StringTable strings = new StringTable();
    public List<ContentRecord> contents = new ArrayList<>();
    public List<Board> boards = new ArrayList<>();
    public List<AssetRef> assets = new ArrayList<>();

    /** Project folder on disk; null until the project is saved. */
    public transient Path root;
    /** Where the project was imported from, for display only. */
    public String importedFrom = "";
    /** Mindustry version whose sources produced the schemas, e.g. "160.5". */
    public String schemaProfile = "160.5";

    public String internalName() {
        return meta.internalName();
    }

    public ContentRecord contentById(String id) {
        for (ContentRecord record : contents) {
            if (record.id != null && record.id.equals(id)) {
                return record;
            }
        }
        return null;
    }

    /** Content names are unique per type because they become file names. */
    public ContentRecord contentByName(dev.modmaker.core.fmt.ContentType type, String name) {
        for (ContentRecord record : contents) {
            if (record.type == type && record.name.equals(name)) {
                return record;
            }
        }
        return null;
    }

    public Set<String> assetPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (AssetRef asset : assets) {
            paths.add(asset.path);
        }
        return paths;
    }

    public String displayName() {
        return meta.displayName == null || meta.displayName.isEmpty() ? meta.name : meta.displayName;
    }
}
