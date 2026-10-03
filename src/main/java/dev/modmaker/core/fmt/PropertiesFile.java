package dev.modmaker.core.fmt;

import dev.modmaker.core.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Order-preserving reader/writer for Java .properties, the format of bundles/&lt;name&gt;.properties.
 *
 * <p>The game merges bundles additively with PropertiesUtils.load (Mods.java:651-663) and vanilla
 * bundles ship raw UTF-8 text, so values are written as-is in UTF-8 and only structurally required
 * characters are escaped.
 */
public final class PropertiesFile {

    public final LinkedHashMap<String, String> values = new LinkedHashMap<>();

    public static PropertiesFile parse(String text) {
        PropertiesFile file = new PropertiesFile();
        for (String line : logicalLines(Json.stripBom(text))) {
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') {
                continue;
            }
            int separator = findSeparator(trimmed);
            String rawKey;
            String rawValue;
            if (separator < 0) {
                rawKey = trimmed;
                rawValue = "";
            } else {
                rawKey = trimmed.substring(0, separator);
                String rest = trimmed.substring(separator);
                int i = 0;
                while (i < rest.length() && Character.isWhitespace(rest.charAt(i))) {
                    i++;
                }
                if (i < rest.length() && (rest.charAt(i) == '=' || rest.charAt(i) == ':')) {
                    i++;
                }
                rawValue = rest.substring(i).stripLeading();
            }
            file.values.put(unescape(rawKey.trim()), unescape(rawValue));
        }
        return file;
    }

    public String write() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.append(escapeKey(entry.getKey())).append('=').append(escapeValue(entry.getValue())).append('\n');
        }
        return out.toString();
    }

    public void put(String key, String value) {
        values.put(key, value);
    }

    /** Splits into logical lines, joining backslash continuations (leading blanks are dropped). */
    private static List<String> logicalLines(String text) {
        String[] physical = text.split("\r\n|\n|\r", -1);
        List<String> logical = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean continuing = false;
        for (String line : physical) {
            String part = continuing ? line.stripLeading() : line;
            if (continues(part)) {
                current.append(part, 0, part.length() - 1);
                continuing = true;
            } else {
                current.append(part);
                logical.add(current.toString());
                current.setLength(0);
                continuing = false;
            }
        }
        if (current.length() > 0) {
            logical.add(current.toString());
        }
        return logical;
    }

    private static boolean continues(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static int findSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                return i;
            }
        }
        return -1;
    }

    static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < text.length()) {
                        try {
                            out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException badHex) {
                            out.append(next);
                        }
                    } else {
                        out.append(next);
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    static String escapeKey(String key) {
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean structural = c == '\\' || c == '=' || c == ':' || c == '#' || c == '!'
                || c == '\n' || c == '\r' || c == '\t'
                || (c == ' ' && (i == 0 || i == key.length() - 1));
            if (structural) {
                out.append('\\');
            }
            out.append(c == '\n' ? 'n' : c == '\r' ? 'r' : c == '\t' ? 't' : c);
        }
        return out.toString();
    }

    static String escapeValue(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (i == 0 && (c == ' ' || c == '#' || c == '!')) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        return out.toString();
    }
}
