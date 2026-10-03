package dev.modmaker.core.model;

/**
 * A reference between two content nodes.
 *
 * <p>The edge is the drawn form of a field on {@link #from} that points at {@link #to}
 * (for example {@code requirements} or {@code research}); {@link #fieldKey} names that field so the
 * reference can be written back into the field tree.
 */
public final class CanvasEdge {
    public String id;
    public String from;
    public String to;
    public String fieldKey;
    public String label = "";
}
