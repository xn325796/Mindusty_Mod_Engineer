package dev.modmaker.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Tolerant reader for the JSON/HJSON dialect Mindustry accepts.
 *
 * <p>Every meta and content file goes through the game's HJSON parser (ContentParser.java:1026 uses
 * Jval.read on the file text), so real mods rely on the lenient forms: slash-slash and hash
 * comments, slash-star blocks, unquoted keys, single quotes, trailing commas, and - most
 * importantly - members separated only by newlines. This reader accepts all of them.
 *
 * <p>The game escapes stray hash characters in .json files before parsing them
 * (ContentParser.java:1026-1029); a backslash-escaped character that is not a known escape is
 * therefore taken literally here too.
 */
public final class HjsonReader {

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    /** Caps nesting so a hostile file cannot overflow the stack. */
    private static final int MAX_DEPTH = 256;

    private final String src;
    private int pos;
    private int depth;

    private HjsonReader(String src) {
        this.src = src;
    }

    public static JsonElement read(String text) {
        HjsonReader reader = new HjsonReader(Json.stripBom(text));
        reader.skipTrivia();
        JsonElement value = reader.parseValue();
        reader.skipTrivia();
        if (!reader.atEnd()) {
            throw reader.error("Unexpected trailing content");
        }
        return value;
    }

    public static JsonObject readObject(String text) {
        JsonElement element = read(text);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    // --- values ---------------------------------------------------------------------------------

    private JsonElement parseValue() {
        if (++depth > MAX_DEPTH) {
            throw error("JSON nested more than " + MAX_DEPTH + " levels deep");
        }
        try {
            return parseValueInner();
        } finally {
            depth--;
        }
    }

    private JsonElement parseValueInner() {
        skipTrivia();
        if (atEnd()) {
            throw error("Unexpected end of input");
        }
        char c = peek();
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '"') {
            return new JsonPrimitive(parseQuoted('"'));
        }
        if (c == '\'') {
            return src.startsWith("'''", pos)
                ? new JsonPrimitive(parseRawString())
                : new JsonPrimitive(parseQuoted('\''));
        }
        return parseBare();
    }

    private JsonObject parseObject() {
        expect('{');
        JsonObject object = new JsonObject();
        while (true) {
            skipTrivia();
            if (atEnd()) {
                throw error("Unterminated object");
            }
            if (peek() == '}') {
                pos++;
                return object;
            }
            String key = parseKey();
            skipTrivia();
            expect(':');
            object.add(key, parseValue());
            skipTrivia();
            if (!atEnd() && peek() == ',') {
                pos++;
            }
        }
    }

    private JsonArray parseArray() {
        expect('[');
        JsonArray array = new JsonArray();
        while (true) {
            skipTrivia();
            if (atEnd()) {
                throw error("Unterminated array");
            }
            if (peek() == ']') {
                pos++;
                return array;
            }
            array.add(parseValue());
            skipTrivia();
            if (!atEnd() && peek() == ',') {
                pos++;
            }
        }
    }

    private String parseKey() {
        skipTrivia();
        char c = peek();
        if (c == '"') {
            return parseQuoted('"');
        }
        if (c == '\'') {
            return parseQuoted('\'');
        }
        int start = pos;
        while (!atEnd()) {
            char current = peek();
            if (current == ':' || current == '\n' || current == '\r' || current == '{' || current == '}') {
                break;
            }
            pos++;
        }
        String key = src.substring(start, pos).trim();
        if (key.isEmpty()) {
            throw error("Expected a key");
        }
        return key;
    }

    private String parseQuoted(char quote) {
        expect(quote);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == quote) {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (atEnd()) {
                throw error("Unterminated escape");
            }
            char escaped = src.charAt(pos++);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '/' -> out.append('/');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case '\'' -> out.append('\'');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("Truncated unicode escape");
                    }
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException badHex) {
                        throw error("Bad unicode escape \\u" + hex);
                    }
                }
                // The game rewrites bare '#' to '\#' before parsing, so an unknown escape is literal.
                default -> out.append(escaped);
            }
        }
    }

    private String parseRawString() {
        pos += 3;
        int end = src.indexOf("'''", pos);
        if (end < 0) {
            throw error("Unterminated raw string");
        }
        String value = src.substring(pos, end);
        pos = end + 3;
        return value.strip();
    }

    private JsonElement parseBare() {
        int start = pos;
        while (!atEnd()) {
            char c = peek();
            if (c == ',' || c == '}' || c == ']' || c == '\n' || c == '\r') {
                break;
            }
            if (c == '/' && pos + 1 < src.length()
                && (src.charAt(pos + 1) == '/' || src.charAt(pos + 1) == '*')) {
                break;
            }
            pos++;
        }
        String token = src.substring(start, pos).trim().replace("\\#", "#");
        if (token.isEmpty()) {
            throw error("Expected a value");
        }
        if (token.equals("null")) {
            return JsonNull.INSTANCE;
        }
        if (token.equals("true") || token.equals("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(token));
        }
        if (NUMBER.matcher(token).matches()) {
            boolean integral = token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0;
            try {
                return new JsonPrimitive(integral ? Long.valueOf(Long.parseLong(token)) : new BigDecimal(token));
            } catch (NumberFormatException ignored) {
                // Falls through to a string for absurdly large magnitudes.
            }
        }
        return new JsonPrimitive(token);
    }

    // --- trivia ---------------------------------------------------------------------------------

    private void skipTrivia() {
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                pos++;
                continue;
            }
            if (c == '/' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                if (next == '/') {
                    skipToLineEnd();
                    continue;
                }
                if (next == '*') {
                    skipBlockComment();
                    continue;
                }
            }
            if (c == '#' && atLineStart()) {
                skipToLineEnd();
                continue;
            }
            return;
        }
    }

    private void skipToLineEnd() {
        while (!atEnd() && peek() != '\n') {
            pos++;
        }
    }

    private void skipBlockComment() {
        pos += 2;
        while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
            pos++;
        }
        pos = Math.min(pos + 2, src.length());
    }

    private boolean atLineStart() {
        for (int i = pos - 1; i >= 0; i--) {
            char c = src.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    // --- primitives -----------------------------------------------------------------------------

    private void expect(char expected) {
        skipTrivia();
        if (atEnd() || peek() != expected) {
            throw error("Expected '" + expected + "'");
        }
        pos++;
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new IllegalArgumentException(message + " at line " + line + ", column " + column);
    }
}
