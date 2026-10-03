package dev.modmaker.core.io;

import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.json.HjsonReader;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.model.ModMeta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a mod the way the game does: a folder or a zip/jar, unwrapping a single top-level folder
 * (Mods.resolveRoot, Mods.java:1080), recognising the four meta file names in order (Mods.java:35),
 * scanning content/&lt;folder&gt; recursively with the historical plural aliases (Mods.java:877-885)
 * and picking up bundles/bundle*.properties.
 *
 * <p>Files that are not content or bundles are kept as assets so an untouched mod can be rebuilt
 * exactly. OS and VCS junk is dropped instead.
 */
public final class ModPackageReader {

    private static final List<String> NOISE_FILES = List.of(".DS_Store", "desktop.ini", "Thumbs.db");
    private static final List<String> NOISE_DIRS =
        List.of("__MACOSX/", ".git/", ".idea/", ".vscode/", ".svn/", "modmaker/");
    private static final String CONTENT = "content/";
    private static final String BUNDLES = "bundles/";

    private ModPackageReader() {
    }

    public static ModPackage read(Path source) throws IOException {
        if (Files.isDirectory(source)) {
            return readFolder(source);
        }
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".jar")) {
            return readZip(source);
        }
        throw new IOException("Not a mod folder or zip: " + source);
    }

    private static ModPackage readFolder(Path root) throws IOException {
        ModPackage mod = new ModPackage();
        mod.sourceLabel = root.toAbsolutePath().toString();
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> dirs = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.isEmpty()) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    dirs.add(relative);
                } else if (Files.isRegularFile(path)) {
                    files.put(relative, Files.readAllBytes(path));
                }
            }
        }
        mod.emptyDirs.addAll(emptyDirs(dirs, files.keySet()));
        classify(mod, files);
        return mod;
    }

    private static ModPackage readZip(Path zip) throws IOException {
        ModPackage mod = new ModPackage();
        mod.sourceLabel = zip.toAbsolutePath().toString();
        mod.fromZip = true;

        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> dirs = new ArrayList<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) {
                    dirs.add(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                    continue;
                }
                names.add(name);
            }

            String prefix = singleRootPrefix(names);
            mod.rootPrefix = prefix;

            for (String name : names) {
                if (!prefix.isEmpty() && !name.startsWith(prefix)) {
                    continue;
                }
                String relative = prefix.isEmpty() ? name : name.substring(prefix.length());
                if (relative.isEmpty() || relative.endsWith("/")) {
                    continue;
                }
                ZipEntry entry = archive.getEntry(name);
                if (entry == null) {
                    continue;
                }
                try (InputStream stream = archive.getInputStream(entry)) {
                    files.put(relative, stream.readAllBytes());
                }
            }
            // A zip built elsewhere may only carry directory entries for empty folders.
            dirs.removeIf(dir -> !prefix.isEmpty() && !dir.startsWith(prefix));
            List<String> stripped = new ArrayList<>();
            for (String dir : dirs) {
                String relative = prefix.isEmpty() ? dir : dir.substring(prefix.length());
                if (!relative.isEmpty()) {
                    stripped.add(relative);
                }
            }
            mod.emptyDirs.addAll(emptyDirs(stripped, files.keySet()));
        }
        classify(mod, files);
        return mod;
    }

    /** Directories that contain no file anywhere beneath them. */
    private static List<String> emptyDirs(List<String> directories, Set<String> filePaths) {
        Set<String> withFiles = new LinkedHashSet<>();
        for (String path : filePaths) {
            int slash = path.lastIndexOf('/');
            while (slash > 0) {
                withFiles.add(path.substring(0, slash));
                slash = path.lastIndexOf('/', slash - 1);
            }
        }
        List<String> empty = new ArrayList<>();
        for (String dir : new LinkedHashSet<>(directories)) {
            if (!withFiles.contains(dir) && !isNoise(dir + "/")) {
                empty.add(dir);
            }
        }
        return empty;
    }

    /**
     * Mods.resolveRoot descends into the only top-level folder when there is exactly one entry.
     * Returns the prefix to strip, e.g. "MyMod/", or an empty string.
     */
    static String singleRootPrefix(List<String> entryNames) {
        Set<String> tops = new LinkedHashSet<>();
        for (String name : entryNames) {
            int slash = name.indexOf('/');
            if (slash < 0) {
                return "";
            }
            tops.add(name.substring(0, slash));
            if (tops.size() > 1) {
                return "";
            }
        }
        if (tops.size() != 1) {
            return "";
        }
        String only = tops.iterator().next();
        return only + "/";
    }

    private static void classify(ModPackage mod, Map<String, byte[]> files) {
        // The meta file the game would pick: first existing name in its probe order.
        for (String candidate : ModMeta.META_FILES) {
            byte[] bytes = files.get(candidate);
            if (bytes != null) {
                mod.metaFile = candidate;
                // Kept verbatim, BOM included, so an untouched meta is written back byte for byte.
                // Every parser below strips the BOM itself (HjsonReader.read).
                mod.metaText = new String(bytes, StandardCharsets.UTF_8);
                try {
                    mod.meta = ModMetaCodec.fromJson(HjsonReader.readObject(mod.metaText));
                } catch (RuntimeException failure) {
                    mod.metaError = failure.getMessage();
                    mod.skipped.add(candidate + " (unreadable meta: " + failure.getMessage() + ")");
                }
                break;
            }
        }

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (isNoise(path)) {
                mod.skipped.add(path);
                continue;
            }
            if (path.equals(mod.metaFile)) {
                continue;
            }

            if (path.startsWith(CONTENT)) {
                ModPackage.SourceContent content = readContent(path, entry.getValue());
                if (content != null) {
                    mod.contents.add(content);
                    continue;
                }
            }
            if (path.startsWith(BUNDLES) && BundleCodec.isBundleFile(fileName)) {
                mod.bundleFiles.put(path, new String(entry.getValue(), StandardCharsets.UTF_8));
                continue;
            }
            mod.assetFiles.put(path, entry.getValue());
        }
    }

    /** Returns null when the file is not a content json, so it falls through to assets. */
    private static ModPackage.SourceContent readContent(String path, byte[] bytes) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ext.equals("json") && !ext.equals("hjson")) {
            return null;
        }
        String rest = path.substring(CONTENT.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String folder = rest.substring(0, slash);
        ContentType type = ContentType.fromFolderLoose(folder);
        if (type == null) {
            return null;
        }

        ModPackage.SourceContent content = new ModPackage.SourceContent();
        content.path = path;
        content.name = dot < 0 ? fileName : fileName.substring(0, dot);
        content.ext = ext;
        content.type = type;
        // Verbatim text (BOM included) so untouched files round-trip byte for byte; parsing strips.
        content.text = new String(bytes, StandardCharsets.UTF_8);
        try {
            JsonObject object = HjsonReader.readObject(content.text);
            for (Map.Entry<String, com.google.gson.JsonElement> field : object.entrySet()) {
                content.fields.put(field.getKey(), field.getValue());
            }
        } catch (RuntimeException failure) {
            content.parseError = failure.getMessage();
        }
        return content;
    }

    private static boolean isNoise(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (NOISE_FILES.contains(fileName)) {
            return true;
        }
        for (String dir : NOISE_DIRS) {
            if (path.startsWith(dir) || path.contains("/" + dir)) {
                return true;
            }
        }
        return false;
    }
}
