package dev.modmaker.core.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON text helpers. Output is plain JSON, which HJSON parsers (including the game's) accept. */
public final class Json {
    private static final Gson WRITER = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private Json() {
    }

    /** Reads a file as UTF-8 and drops a leading BOM - real mod files do ship one. */
    public static String readFile(Path file) throws IOException {
        return stripBom(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    public static void writeFile(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Parses either strict JSON or the tolerant HJSON dialect. */
    public static JsonElement parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text is null");
        }
        return HjsonReader.read(text);
    }

    public static JsonObject parseObject(String text) {
        JsonElement element = parse(text);
        if (!element.isJsonObject()) {
            throw new JsonSyntaxException("Expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    /** Strict parse, for project files this application wrote itself. */
    public static JsonObject parseStrictObject(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    public static String write(JsonElement element) {
        return WRITER.toJson(element);
    }

    public static String write(Object value) {
        return WRITER.toJson(value);
    }
}
