package dev.modmaker.ui;

import dev.modmaker.core.io.ModExporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The verification loop: build the mod into a throwaway data directory and start Mindustry against
 * it.
 *
 * <p>ClientLauncher.java:42 honours MINDUSTRY_DATA_DIR (or -Dmindustry.data.dir), so the game reads
 * its mods, saves and logs from a scratch folder. That means a test run never touches the player's
 * real profile, and the game's own last_log.txt can be read back to report content errors.
 */
public final class GameLauncher {

    public record Session(Process process, Path dataDir, Path logFile, Path modFolder) {
    }

    private GameLauncher() {
    }

    /** Builds the project into scratch/mods/&lt;name&gt;/ and launches the game on it. */
    public static Session buildAndLaunch(ProjectController controller, Path gameJar, Path javaExe,
        Consumer<String> log) throws IOException {
        Path dataDir = Files.createTempDirectory("modmaker-game-");
        Path modsDir = dataDir.resolve("mods");
        Files.createDirectories(modsDir);

        var report = ModExporter.buildFolder(controller.project(), modsDir, controller.validate());
        log.accept("installed " + report.files().size() + " files into " + report.target());
        log.accept("game data directory: " + dataDir);

        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.add("-Xms512m");
        command.add("-Xmx2G");
        command.add("-jar");
        command.add(gameJar.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(gameJar.toAbsolutePath().getParent().toFile());
        builder.environment().put("MINDUSTRY_DATA_DIR", dataDir.toAbsolutePath().toString());
        Path consoleLog = dataDir.resolve("console.log");
        builder.redirectErrorStream(true);
        builder.redirectOutput(consoleLog.toFile());
        Process process = builder.start();
        log.accept("launched: " + String.join(" ", command));

        Path gameLog = dataDir.resolve("last_log.txt");
        Thread watcher = new Thread(() -> {
            try {
                int exit = process.waitFor();
                log.accept("game exited with code " + exit);
                if (Files.exists(gameLog)) {
                    readProblems(gameLog).forEach(log);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                log.accept("could not read the game log: " + failure.getMessage());
            }
        }, "modmaker-game-watcher");
        watcher.setDaemon(true);
        watcher.start();

        return new Session(process, dataDir, gameLog, Path.of(report.target()));
    }

    /** Lines from the game log that indicate content or sprite problems. */
    public static List<String> readProblems(Path logFile) throws IOException {
        List<String> problems = new ArrayList<>();
        for (String line : Files.readAllLines(logFile)) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error loading content") || lower.contains("sprite not found")
                || lower.contains("failed to load") || lower.contains("no parsers for content type")
                || lower.contains("has errored")) {
                problems.add("[game] " + line.strip());
            }
        }
        if (problems.isEmpty()) {
            problems.add("[game] no content errors reported in " + logFile.getFileName());
        }
        return problems;
    }

    /** Finds a java executable: JAVA_HOME first, then the usual Windows install locations. */
    public static Path detectJava() {
        String home = System.getenv("JAVA_HOME");
        if (home != null) {
            Path candidate = Path.of(home, "bin", "java.exe");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        for (Path root : List.of(Path.of("C:/Program Files/Java"), Path.of("C:/Program Files/Eclipse Adoptium"),
            Path.of("C:/Program Files/Microsoft"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 3)) {
                Path found = walk.filter(path -> path.getFileName().toString().equals("java.exe"))
                    .filter(path -> path.getParent().getFileName().toString().equals("bin"))
                    .findFirst().orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IOException ignored) {
                // Fall through to the next root.
            }
        }
        return null;
    }

    /** Finds the game jar: ../Mindustry/Mindustry.jar relative to the working directory. */
    public static Path detectGameJar() {
        List<Path> candidates = List.of(
            Path.of("..", "Mindustry", "Mindustry.jar"),
            Path.of("Mindustry", "Mindustry.jar"),
            Path.of("..", "..", "Mindustry", "Mindustry.jar"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }
}
