package dev.modmaker.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Locates the real-mod fixture used by the golden tests, wherever it currently sits in the
 * workspace: the folder has been moved around before, so the tests probe several candidates instead
 * of hard-coding one.
 */
public final class TestFixtures {

    private static final List<Path> CANDIDATES = List.of(
        Paths.get("..", "格雷工业"),
        Paths.get("..", "格雷工业v167W45d", "格雷工业"),
        Paths.get("..", "格雷工业v167W45d"),
        Paths.get("格雷工业"));

    private TestFixtures() {
    }

    /** The folder holding the mod's mod.json, or null when the fixture is absent. */
    public static Path realModRoot() {
        for (Path candidate : CANDIDATES) {
            Path resolved = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(resolved) && Files.exists(resolved.resolve("mod.json"))) {
                return resolved;
            }
            // Tolerate one wrapping folder, like the game's resolveRoot does.
            if (Files.isDirectory(resolved)) {
                try (var stream = Files.list(resolved)) {
                    Path only = stream.filter(Files::isDirectory).findFirst().orElse(null);
                    if (only != null && Files.exists(only.resolve("mod.json"))) {
                        return only;
                    }
                } catch (IOException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return null;
    }
}
