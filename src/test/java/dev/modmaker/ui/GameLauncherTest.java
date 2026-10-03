package dev.modmaker.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game loop plumbing: finding a JVM, finding the game jar and recognising content errors in the
 * game's own log. Nothing here launches anything, so it is safe to run anywhere.
 */
class GameLauncherTest {

    @Test
    void findsAJavaRuntimeOnThisMachine() {
        Path java = GameLauncher.detectJava();
        assertTrue(java == null || Files.exists(java), "detected java must exist when not null: " + java);
        if (java != null) {
            assertTrue(java.getFileName().toString().startsWith("java"), java.toString());
        }
    }

    @Test
    void findsTheGameJarNextToTheWorkspace() {
        Path jar = GameLauncher.detectGameJar();
        if (jar != null) {
            assertTrue(Files.exists(jar));
            assertTrue(jar.getFileName().toString().equals("Mindustry.jar"), jar.toString());
        }
    }

    @Test
    void recognisesContentErrorsInAGameLog() throws IOException {
        Path log = Files.createTempFile("modmaker-log", ".txt");
        Files.writeString(log, """
            10:00:00 [INFO] Loading mods...
            10:00:01 [ERR] Error loading content: content/items/broken.json
            10:00:01 [WARN] Sprite not found: 'my-mod-missing'
            10:00:02 [INFO] Mod loaded: my-mod
            """);
        List<String> problems = GameLauncher.readProblems(log);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("[game]"));
        Files.deleteIfExists(log);
    }

    @Test
    void reportsACleanLogAsClean() throws IOException {
        Path log = Files.createTempFile("modmaker-clean", ".txt");
        Files.writeString(log, "10:00:00 [INFO] Mod loaded: my-mod\n");
        List<String> problems = GameLauncher.readProblems(log);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("no content errors"));
        assertFalse(problems.get(0).contains("Sprite"));
        Files.deleteIfExists(log);
    }
}
