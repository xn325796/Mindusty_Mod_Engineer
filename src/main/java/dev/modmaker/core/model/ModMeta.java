package dev.modmaker.core.model;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.NameRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mod.json / mod.hjson contents, mirroring Mods.ModMeta (Mods.java:1392-1416).
 *
 * <p>Unknown keys are kept in {@link #extra} so a mod that declares fields this application does not
 * model still round-trips unchanged.
 */
public final class ModMeta {
    public String name = "";
    public String displayName = "";
    public String author = "";
    public String description = "";
    public String subtitle = "";
    public String version = "1.0";
    public String minGameVersion = "146";
    public List<String> dependencies = new ArrayList<>();
    public List<String> softDependencies = new ArrayList<>();
    public boolean hidden;
    public String repo = "";
    public double texturescale = 1;
    public List<String> contentOrder = new ArrayList<>();

    /** Class-mod fields, preserved for round-trip; class mods themselves are out of scope. */
    public String mainClass = "";
    public boolean java;
    public boolean kotlin;

    public Map<String, JsonElement> extra = new LinkedHashMap<>();

    /** The prefix of every content name, sprite region and bundle key (Mods.ModMeta.cleanup). */
    public String internalName() {
        return NameRules.internalName(name);
    }

    /** The meta file name, honouring the order the game probes (Mods.java:35). */
    public static final String[] META_FILES = {"mod.json", "mod.hjson", "plugin.json", "plugin.hjson"};
}
