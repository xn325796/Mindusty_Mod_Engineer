package dev.modmaker.core.model;

/**
 * A binary or non-content file owned by the project and copied verbatim into the built mod:
 * sprites, scripts, sounds, music, maps, schematics, icon.
 */
public final class AssetRef {
    public String path;
    public long size;
    /** sprites | sprites-override | scripts | sounds | music | maps | schematics | icon */
    public String kind = "";

    public AssetRef() {
    }

    public AssetRef(String path, long size, String kind) {
        this.path = path;
        this.size = size;
        this.kind = kind;
    }
}
