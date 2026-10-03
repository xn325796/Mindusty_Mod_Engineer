package dev.modmaker.core.io;

import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.validate.Issue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the distributable mod from a saved project folder.
 *
 * <p>Because a project folder already is a mod folder, building is a copy: everything except the
 * authoring directory goes into &lt;destination&gt;/&lt;internalName&gt;/, or into a zip whose single
 * wrapping folder the game unwraps on load (Mods.resolveRoot, Mods.java:1080) and that a user can
 * unpack straight into mods/.
 */
public final class ModExporter {

    /** Fixed timestamp so repeated builds of the same project produce identical archives. */
    private static final long FIXED_TIME =
        LocalDateTime.of(2020, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();

    private ModExporter() {
    }

    /** Copies the mod into destination/&lt;internalName&gt;/ and returns what was written. */
    public static ExportReport buildFolder(ModProject project, Path destination, List<Issue> issues)
        throws IOException {
        Path root = requireRoot(project);
        String internal = requireInternalName(project);
        Path modDir = destination.resolve(internal);
        Files.createDirectories(modDir);

        // Directories first, so empty ones (an empty sprites-override/, say) survive too.
        for (Path dir : modDirectories(root)) {
            Files.createDirectories(modDir.resolve(relative(root, dir)));
        }

        List<String> written = new ArrayList<>();
        long bytes = 0;
        for (Path file : modFiles(root)) {
            String relative = relative(root, file);
            Path target = modDir.resolve(relative);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            written.add(relative);
            bytes += Files.size(file);
        }
        return new ExportReport(modDir.toAbsolutePath().toString(), false, written, bytes,
            issues == null ? List.of() : issues);
    }

    /** Packs the mod into a zip with one wrapping folder, so unpacking gives mods/&lt;name&gt;/. */
    public static ExportReport buildZip(ModProject project, Path zipFile, List<Issue> issues)
        throws IOException {
        Path root = requireRoot(project);
        String internal = requireInternalName(project);
        Files.createDirectories(zipFile.toAbsolutePath().getParent());

        List<Path> files = modFiles(root);
        Set<Path> dirsWithFiles = directoriesWithFiles(root, files);

        List<String> written = new ArrayList<>();
        long bytes = 0;
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile),
            StandardCharsets.UTF_8)) {
            out.setLevel(Deflater.BEST_COMPRESSION);
            for (Path file : files) {
                String relative = relative(root, file);
                ZipEntry entry = new ZipEntry(internal + "/" + relative);
                entry.setTime(FIXED_TIME);
                out.putNextEntry(entry);
                byte[] data = Files.readAllBytes(file);
                out.write(data);
                out.closeEntry();
                written.add(relative);
                bytes += data.length;
            }
            // Empty directories still need an entry, otherwise unpacking loses them.
            for (Path dir : modDirectories(root)) {
                if (dirsWithFiles.contains(dir)) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(internal + "/" + relative(root, dir) + "/");
                entry.setTime(FIXED_TIME);
                out.putNextEntry(entry);
                out.closeEntry();
            }
        }
        return new ExportReport(zipFile.toAbsolutePath().toString(), true, written, bytes,
            issues == null ? List.of() : issues);
    }

    /** Every file of the mod as it sits in the project folder, excluding the authoring directory. */
    public static List<Path> modFiles(Path projectRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.filter(Files::isRegularFile)
                .filter(file -> !isAuthoring(projectRoot, file))
                .sorted()
                .toList();
        }
    }

    /** Every directory of the mod, excluding the authoring directory tree. */
    public static List<Path> modDirectories(Path projectRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.filter(Files::isDirectory)
                .filter(dir -> !projectRoot.equals(dir))
                .filter(dir -> !isAuthoring(projectRoot, dir))
                .sorted()
                .toList();
        }
    }

    private static Set<Path> directoriesWithFiles(Path root, List<Path> files) {
        Set<Path> dirs = new java.util.HashSet<>();
        for (Path file : files) {
            Path parent = file.getParent();
            while (parent != null && parent.startsWith(root)) {
                dirs.add(parent);
                parent = parent.getParent();
            }
        }
        return dirs;
    }

    private static boolean isAuthoring(Path projectRoot, Path file) {
        Path relative = projectRoot.relativize(file);
        return relative.getNameCount() > 0
            && relative.getName(0).toString().equalsIgnoreCase(ProjectIo.AUTHORING_DIR);
    }

    private static Path requireRoot(ModProject project) throws IOException {
        if (project.root == null || !Files.isDirectory(project.root)) {
            throw new IOException("project has no folder on disk; save it before building");
        }
        return project.root;
    }

    private static String requireInternalName(ModProject project) throws IOException {
        String internal = project.internalName();
        if (internal.isEmpty()) {
            throw new IOException("mod name is empty; the built folder needs a name");
        }
        if (internal.contains(" ") || !internal.equals(internal.toLowerCase(Locale.ROOT))) {
            throw new IOException("mod name does not clean up to a folder-safe name: " + internal);
        }
        return internal;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
