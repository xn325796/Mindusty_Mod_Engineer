package dev.modmaker.core.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an import or export did, in a form the UI can show and tests can assert on. */
public final class ImportReport {

    public String source = "";
    public int contentCount;
    public int inlineCount;
    public int spriteCount;
    public int assetCount;
    public int bundleCount;
    public int stringCount;
    public int edgeCount;
    public int patchCount;
    public List<String> warnings = new ArrayList<>();
    /** Content files that failed to parse; imported as raw text so nothing is lost. */
    public Map<String, String> parseFailures = new LinkedHashMap<>();
    public List<String> skipped = new ArrayList<>();

    public void warn(String message) {
        warnings.add(message);
    }

    public String summary() {
        return "content=" + contentCount + " inline=" + inlineCount + " patches=" + patchCount
            + " edges=" + edgeCount + " sprites=" + spriteCount + " assets=" + assetCount
            + " bundles=" + bundleCount + " strings=" + stringCount
            + " parseFailures=" + parseFailures.size() + " warnings=" + warnings.size();
    }
}
