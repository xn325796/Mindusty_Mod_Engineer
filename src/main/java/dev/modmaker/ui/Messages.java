package dev.modmaker.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * UI strings. messages.properties is the English source, messages_&lt;locale&gt;.properties are the
 * translations (Java ResourceBundle falls back to the base bundle for missing locales).
 */
public final class Messages {
    private static final List<Runnable> listeners = new ArrayList<>();
    private static ResourceBundle bundle =
        ResourceBundle.getBundle("messages", Locale.getDefault());

    private Messages() {
    }

    public static String t(String key) {
        return bundle.containsKey(key) ? bundle.getString(key) : key;
    }

    public static String t(String key, Object... args) {
        return java.text.MessageFormat.format(t(key), args);
    }

    public static Locale locale() {
        return bundle.getLocale();
    }

    public static void setLocale(Locale locale) {
        bundle = ResourceBundle.getBundle("messages", locale);
        listeners.forEach(Runnable::run);
    }

    /** Registers a callback invoked after the locale changes, so views can rebuild their labels. */
    public static void onChange(Runnable listener) {
        listeners.add(listener);
    }
}
