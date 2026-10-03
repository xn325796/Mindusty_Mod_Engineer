package dev.modmaker.core.model;

import java.util.LinkedHashMap;

/**
 * Localised strings, split into the keys this tool manages and everything else found in the mod's
 * bundles.
 *
 * <p>Managed keys follow the game's convention
 * {@code <type>.<internalModName>-<contentName>.<name|description|details|credit>}
 * (UnlockableContent.java:90). Extra keys - {@code category.*}, {@code stat.*}, script keys - are
 * kept per locale so they are written back untouched.
 */
public final class StringTable {
    /** key -> locale -> value. */
    public LinkedHashMap<String, LinkedHashMap<String, String>> values = new LinkedHashMap<>();

    /** locale -> ordered bundle entries this tool does not manage. */
    public LinkedHashMap<String, LinkedHashMap<String, String>> extra = new LinkedHashMap<>();

    /** Bundle file base names seen per locale, e.g. "zh_CN" -> "bundle_zh_CN.properties". */
    public LinkedHashMap<String, String> bundleFiles = new LinkedHashMap<>();

    /** Original bundle text per locale, written back verbatim while that bundle is untouched. */
    public LinkedHashMap<String, String> bundleRaw = new LinkedHashMap<>();

    /** Locales whose bundle text no longer matches what is on disk. */
    public LinkedHashMap<String, Boolean> bundleDirty = new LinkedHashMap<>();

    public boolean isBundleDirty(String locale) {
        return Boolean.TRUE.equals(bundleDirty.get(locale));
    }

    public void markBundleDirty(String locale) {
        bundleDirty.put(locale, true);
    }

    public void put(String key, String locale, String value) {
        values.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(locale, value);
    }

    public String get(String key, String locale) {
        LinkedHashMap<String, String> byLocale = values.get(key);
        return byLocale == null ? null : byLocale.get(locale);
    }

    public void removeKey(String key) {
        values.remove(key);
    }
}
