package dev.modmaker.core.fmt;

import dev.modmaker.core.json.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content templates applied when new records are created. The planet template carries vanilla
 * serpulo's default data (grid size, atmosphere, clouds, campaign flags), so a freshly added planet
 * is complete and works in game instead of being an empty sectorSize=0 shell.
 *
 * <p>Templates are HJSON resources under /templates/; each application hands out a freshly parsed
 * copy so records never share mutable JSON subtrees.
 */
public final class PlanetTemplates {

    /** template display name by content type (for log lines). */
    private static final Map<ContentType, String> NAMES = Map.of(ContentType.planet, "serpulo");

    private static final Map<ContentType, String> SOURCES = Map.of(
        ContentType.planet, "/templates/planet-serpulo.json");

    private static final Map<ContentType, String> TEXT_CACHE = new ConcurrentHashMap<>();

    private PlanetTemplates() {
    }

    /** The template's display name, or null when the type has no template. */
    public static String name(ContentType type) {
        return NAMES.get(type);
    }

    /**
     * A fresh copy of the template fields for the type, or null when there is no template or the
     * resource is missing/unparseable. Every call returns a new tree: callers may mutate freely.
     */
    public static com.google.gson.JsonObject templateFor(ContentType type) {
        String source = SOURCES.get(type);
        if (source == null) {
            return null;
        }
        String text = TEXT_CACHE.computeIfAbsent(type, ignored -> load(source));
        if (text == null) {
            return null;
        }
        try {
            return Json.parseObject(text);
        } catch (RuntimeException broken) {
            return null;
        }
    }

    private static String load(String source) {
        try (InputStream in = PlanetTemplates.class.getResourceAsStream(source)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return null;
        }
    }
}
