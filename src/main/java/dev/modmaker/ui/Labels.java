package dev.modmaker.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Chinese-friendly display labels for raw identifiers (content field names, class names), backed by
 * field-labels&lt;locale&gt;.properties and class-labels&lt;locale&gt;.properties. Identifiers without a
 * translation fall back to themselves, so the UI always shows something editable.
 */
public final class Labels {

    private static final Map<Locale, ResourceBundle> FIELD_BUNDLES = new HashMap<>();
    private static final Map<Locale, ResourceBundle> CLASS_BUNDLES = new HashMap<>();

    private Labels() {
    }

    /** Display label for a content JSON field name; identical to {@code key} when untranslated. */
    public static String field(String key) {
        return lookup(FIELD_BUNDLES, "field-labels", key);
    }

    /** Display label for a content class name; identical to {@code name} when untranslated. */
    public static String clazz(String name) {
        return lookup(CLASS_BUNDLES, "class-labels", name);
    }

    /** "{raw} {label}" when a translation exists, otherwise just the raw identifier. */
    public static String withRaw(String raw, String label) {
        return label.equals(raw) ? raw : label + " " + raw;
    }

    private static String lookup(Map<Locale, ResourceBundle> cache, String base, String key) {
        if (key == null) {
            return "";
        }
        ResourceBundle bundle = bundleFor(cache, base);
        try {
            return bundle != null && bundle.containsKey(key) ? bundle.getString(key) : key;
        } catch (MissingResourceException missing) {
            return key;
        }
    }

    private static ResourceBundle bundleFor(Map<Locale, ResourceBundle> cache, String base) {
        Locale locale = Messages.locale();
        return cache.computeIfAbsent(locale, ignored -> {
            try {
                return ResourceBundle.getBundle(base, locale);
            } catch (MissingResourceException missing) {
                return null;
            }
        });
    }
}
