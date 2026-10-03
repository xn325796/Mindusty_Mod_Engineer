package dev.modmaker.core.fmt;

import java.util.Set;

/**
 * Bundle file naming and the content string key convention.
 *
 * <p>Mods.buildFiles (Mods.java:641-663) accepts files inside bundles/ whose name starts with
 * "bundle" and ends in ".properties"; the base name selects the locale, so "bundle.properties" is
 * the fallback and "bundle_zh_CN.properties" holds zh_CN. Keys for content follow
 * {@code <type>.<fullName>.<field>} (UnlockableContent.java:90).
 */
public final class BundleCodec {

    private BundleCodec() {
    }

    public static boolean isBundleFile(String fileName) {
        return fileName.startsWith("bundle") && fileName.endsWith(".properties");
    }

    /** "bundle.properties" to "", "bundle_zh_CN.properties" to "zh_CN". */
    public static String localeOf(String fileName) {
        String base = fileName.substring(0, fileName.length() - ".properties".length());
        int underscore = base.indexOf('_');
        return underscore < 0 ? "" : base.substring(underscore + 1);
    }

    /**
     * English is the game's base bundle, shipped as bundle.properties rather than bundle_en.properties
     * (vanilla core/assets/bundles does the same).
     */
    public static String fileNameOf(String locale) {
        return locale == null || locale.isEmpty() || locale.equals("en")
            ? "bundle.properties"
            : "bundle_" + locale + ".properties";
    }

    /** Parsed form of a managed content key. */
    public record ContentKey(ContentType type, String fullName, String field) {
    }

    /**
     * Parses {@code <type>.<fullName>.<field>}. Returns null for keys this tool does not manage
     * (category.*, stat.*, script keys, or anything shaped differently) so they can be preserved.
     */
    public static ContentKey parseKey(String key) {
        String[] parts = key.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        ContentType type = contentTypeOrNull(parts[0]);
        if (type == null || !type.fileBacked()) {
            return null;
        }
        String field = parts[2];
        if (!Set.of(NameRules.BUNDLE_FIELDS).contains(field)) {
            return null;
        }
        return new ContentKey(type, parts[1], field);
    }

    /** Strips the mod prefix from a bundle key's content name: "my-mod-turret" to "turret". */
    public static String contentNameOf(String fullName, String internalModName) {
        String prefix = internalModName + "-";
        return fullName.startsWith(prefix) ? fullName.substring(prefix.length()) : fullName;
    }

    private static ContentType contentTypeOrNull(String name) {
        for (ContentType type : ContentType.values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
