package dev.modmaker.core.io;

import dev.modmaker.core.validate.Issue;

import java.util.List;

/** What a build produced: where it went, which files it contains and what the validator said. */
public record ExportReport(String target, boolean zip, List<String> files, long bytes, List<Issue> issues) {

    public boolean hasErrors() {
        return issues.stream().anyMatch(Issue::isError);
    }

    public long errorCount() {
        return issues.stream().filter(Issue::isError).count();
    }

    public long warningCount() {
        return issues.stream().filter(issue -> !issue.isError()).count();
    }

    public String summary() {
        return (zip ? "zip" : "folder") + " " + target + " files=" + files.size()
            + " bytes=" + bytes + " errors=" + errorCount() + " warnings=" + warningCount();
    }
}
