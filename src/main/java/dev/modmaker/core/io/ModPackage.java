package dev.modmaker.core.io;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ModMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A mod package as found on disk: metadata, content files, bundles and every other file, still in
 * its raw form. Nothing here is normalised - this is the input side of {@link ModImporter} and the
 * reason untouched files can be written back byte for byte.
 */
public final class ModPackage {

    public ModMeta meta = new ModMeta();
    public String metaFile = "";
    public String metaText = "";
    /** Non-empty when the meta file exists but could not be parsed; the rest of the mod still loads. */
    public String metaError = "";

    public List<SourceContent> contents = new ArrayList<>();

    /** Bundle file path -> raw text, exactly as shipped. */
    public LinkedHashMap<String, String> bundleFiles = new LinkedHashMap<>();

    /** Every asset file, mod-relative path -> bytes (sprites, scripts, sounds, maps, loose files). */
    public LinkedHashMap<String, byte[]> assetFiles = new LinkedHashMap<>();

    /** Paths deliberately not carried over (OS junk, VCS metadata). */
    public List<String> skipped = new ArrayList<>();

    /**
     * Directories with no files anywhere beneath them (an empty sprites-override/, say). They are
     * part of the package a mod ships, so they are recreated on unpack and in builds.
     */
    public List<String> emptyDirs = new ArrayList<>();

    /** True when the package came from a zip/jar rather than a folder. */
    public boolean fromZip;
    /** Single wrapping folder inside a zip, unwrapped the way Mods.resolveRoot does. */
    public String rootPrefix = "";
    /** Human readable origin, for the import report. */
    public String sourceLabel = "";

    public static final class SourceContent {
        public String path;
        public String name;
        public String ext;
        public String text;
        public ContentType type;
        public LinkedHashMap<String, JsonElement> fields = new LinkedHashMap<>();
        /** Set when the file could not be parsed; the record is still imported. */
        public String parseError;
    }
}
