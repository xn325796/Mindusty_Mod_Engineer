package dev.modmaker.core.json;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parser acceptance against a real, large, hand-written mod (955 files) that uses the full HJSON
 * dialect. Skipped when the fixture is not next to the project so the suite stays portable.
 */
class RealModFixtureTest {

    private static final Path MOD_ROOT =
        dev.modmaker.core.io.TestFixtures.realModRoot();

    @Test
    void parsesModMetaWithCommentsAndTrailingComma() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        JsonObject meta = HjsonReader.readObject(Json.readFile(MOD_ROOT.resolve("mod.json")));
        assertEquals("社会主义工业化", meta.get("name").getAsString());
        assertEquals("146", meta.get("minGameVersion").getAsString());
        assertFalse(meta.has("dependencies"), "the commented-out dependency line must not become a key");
    }

    @Test
    void parsesARealItemWrittenWithoutCommas() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        Path item = MOD_ROOT.resolve("content/items/CMB钢.json");
        assumeTrue(Files.exists(item), "fixture item missing");
        JsonObject object = HjsonReader.readObject(Json.readFile(item));
        assertEquals("CMB钢", object.get("name").getAsString());
        assertEquals("123456", object.get("color").getAsString());
        assertEquals("毒泥浆", object.get("research").getAsString());
        assertEquals(2, object.getAsJsonArray("shownPlanets").size());
    }

    @Test
    void mostContentFilesOfARealModParse() throws IOException {
        assumeTrue(MOD_ROOT != null, "real mod fixture not present");
        Path content = MOD_ROOT.resolve("content");
        assumeTrue(Files.isDirectory(content), "real mod fixture not present");

        List<String> failures = new ArrayList<>();
        int total = 0;
        try (Stream<Path> walk = Files.walk(content)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    return name.endsWith(".json") || name.endsWith(".hjson");
                })
                .toList();
            for (Path file : files) {
                total++;
                try {
                    HjsonReader.read(Json.readFile(file));
                } catch (Exception failure) {
                    failures.add(MOD_ROOT.relativize(file) + " -> " + failure.getMessage());
                }
            }
        }

        assertTrue(total > 100, "expected a substantial fixture, saw " + total + " content files");
        System.out.println("parsed " + (total - failures.size()) + "/" + total + " content files");
        failures.forEach(failure -> System.out.println("  FAILED " + failure));
        assertTrue(failures.size() * 10 <= total,
            "more than 10% of content files failed: " + failures.size() + "/" + total);
    }
}
