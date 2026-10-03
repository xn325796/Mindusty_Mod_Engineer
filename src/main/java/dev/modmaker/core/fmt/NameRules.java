package dev.modmaker.core.fmt;

/**
 * Naming rules shared by the importer and the exporter.
 *
 * <p>The mod's internal name is the single source of every prefix in a mod package:
 * Mods.ModMeta.cleanup (Mods.java:1427) computes internalName as
 * {@code name.toLowerCase(Locale.ROOT).replace(" ", "-")}, and every content, sprite region and
 * bundle key is derived from it.
 */
public final class NameRules {
    private NameRules() {
    }

    /** Mods.ModMeta.cleanup: lowercase, spaces become dashes. */
    public static String internalName(String displayName) {
        if (displayName == null) {
            return "";
        }
        return displayName.toLowerCase(java.util.Locale.ROOT).replace(" ", "-");
    }

    /** ContentParser constructs every mod content as {@code mod + "-" + fileNameWithoutExtension}. */
    public static String fullName(String internalModName, String contentName) {
        return internalModName + "-" + contentName;
    }

    /**
     * A content name is a file name, and it also becomes a bundle key fragment and a sprite region
     * name. The game imposes no charset - mods in the wild ship Chinese content names - so only
     * characters that break one of those three uses are rejected.
     */
    public static boolean isValidContentName(String name) {
        return contentNameProblem(name) == null;
    }

    /** Why a content name is unusable, or null when it is fine. */
    public static String contentNameProblem(String name) {
        if (name == null || name.isEmpty()) {
            return "name is empty";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c)) {
                return "name contains whitespace, which breaks the file name and sprite region";
            }
            if (c < 0x20) {
                return "name contains a control character";
            }
            if ("\\/:*?\"<>|".indexOf(c) >= 0) {
                return "name contains '" + c + "', which cannot appear in a file name";
            }
        }
        if (name.startsWith(".") || name.endsWith(".")) {
            return "name must not start or end with '.'";
        }
        return null;
    }

    /** Bundles are Java .properties; keys in the wild only use these characters. */
    public static String bundleKey(ContentType type, String fullName, String field) {
        return type.name() + "." + fullName + "." + field;
    }

    /** UnlockableContent.java:90 — the four bundle fields every content type supports. */
    public static final String[] BUNDLE_FIELDS = {"name", "description", "details", "credit"};

    /**
     * Atlas region a sprite ends up as.
     *
     * <p>Mods.packSprites (Mods.java:404-412): a file in sprites/ is prefixed with the mod name
     * unless the part after its first hyphen already starts with "&lt;mod&gt;-" (that is how
     * category-prefixed names like {@code block-mod-block-full} stay untouched); files in
     * sprites-override/ are never prefixed. The full base name is used, dots included - only the
     * "does this region exist" warning strips at the first dot.
     */
    public static String spriteRegion(String internalModName, String fileNameWithoutExtension, boolean prefixedFolder) {
        if (fileNameWithoutExtension == null || fileNameWithoutExtension.isEmpty()) {
            return "";
        }
        int hyphen = fileNameWithoutExtension.indexOf('-');
        boolean alreadyPrefixed = hyphen != -1
            && fileNameWithoutExtension.substring(hyphen + 1).startsWith(internalModName + "-");
        return (prefixedFolder && !alreadyPrefixed ? internalModName + "-" : "") + fileNameWithoutExtension;
    }

    /** The region name used only for the "overrides a non-existent sprite" check (Mods.java:390). */
    public static String regionLookupName(String fileNameWithoutExtension) {
        int dot = fileNameWithoutExtension.indexOf('.');
        return dot < 0 ? fileNameWithoutExtension : fileNameWithoutExtension.substring(0, dot);
    }
}
