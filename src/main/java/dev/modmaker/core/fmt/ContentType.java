package dev.modmaker.core.fmt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mirrors mindustry.ctype.ContentType, restricted to what a mod can actually declare.
 *
 * <p>Only {@link #fileBacked} types have a JSON parser in ContentParser (the parser map at
 * ContentParser.java:577 registers exactly block, unit, weather, item, liquid, status, sector,
 * planet, team). Any other type written to content/&lt;folder&gt;/ fails to load with
 * "No parsers for content type". Bullets and the other non-file types therefore only exist as
 * inline objects nested inside a host content file.
 */
public enum ContentType {
    item("items", "#eab308", true, "Item"),
    block("blocks", "#3b82f6", true, "Block"),
    liquid("liquids", "#06b6d4", true, "Liquid"),
    status("statuses", "#a855f7", true, "StatusEffect"),
    unit("units", "#ef4444", true, "UnitType"),
    // Weather requires an explicit type in JSON (ContentParser.java:694, 911-913), hence no default.
    weather("weather", "#14b8a6", true, ""),
    sector("sectors", "#22c55e", true, "SectorPreset"),
    planet("planets", "#8b5cf6", true, "Planet"),
    team("teams", "#ec4899", true, "TeamEntry"),
    bullet("bullets", "#f97316", false, "BulletType"),
    unitCommand("unitCommands", "#84cc16", false, "UnitCommand"),
    unitStance("unitStances", "#64748b", false, "UnitStance");

    private final String folderName;
    private final String color;
    private final boolean fileBacked;
    private final String defaultClass;

    ContentType(String folderName, String color, boolean fileBacked, String defaultClass) {
        this.folderName = folderName;
        this.color = color;
        this.fileBacked = fileBacked;
        this.defaultClass = defaultClass;
    }

    /** Java class used when a content file omits "type"; empty when the game requires it. */
    public String defaultClass() {
        return defaultClass;
    }

    /** Exact value of ContentType.folderName in the game. */
    public String folderName() {
        return folderName;
    }

    /** UI accent colour, used for the node card strip. */
    public String color() {
        return color;
    }

    /** Whether content/<folder>/*.json for this type is accepted by the game's content parser. */
    public boolean fileBacked() {
        return fileBacked;
    }

    /** The types a user may create and that end up as their own file. */
    public static List<ContentType> creatable() {
        List<ContentType> out = new ArrayList<>();
        for (ContentType type : values()) {
            if (type.fileBacked) {
                out.add(type);
            }
        }
        return out;
    }

    /**
     * Folder names the loader accepts for this type. Mods.loadContent (Mods.java:877-884) scans both
     * the historical plural form (type name lowercased + "s" when it does not already end in "s")
     * and the current folderName, which differ for status/weather/unitCommand/unitStance.
     */
    public Set<String> acceptedFolders() {
        String lower = name().toLowerCase(Locale.ROOT);
        String legacy = lower.endsWith("s") ? lower : lower + "s";
        Set<String> folders = new LinkedHashSet<>();
        folders.add(legacy);
        folders.add(folderName);
        return folders;
    }

    /** Resolves a scanned content folder name back to its type, or null when the folder is unknown. */
    public static ContentType fromFolder(String folderName) {
        for (ContentType type : values()) {
            if (type.acceptedFolders().contains(folderName)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Case-insensitive variant, for reading mods authored on Windows where the folder check happens
     * to succeed. Callers should warn when the on-disk spelling is not in {@link #acceptedFolders()},
     * because the game would not find that folder on a case-sensitive filesystem.
     */
    public static ContentType fromFolderLoose(String folderName) {
        for (ContentType type : values()) {
            for (String accepted : type.acceptedFolders()) {
                if (accepted.equalsIgnoreCase(folderName)) {
                    return type;
                }
            }
        }
        return null;
    }
}
