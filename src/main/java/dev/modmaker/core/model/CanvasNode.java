package dev.modmaker.core.model;

/**
 * A card on a board.
 *
 * <p>A content node only references a {@link ContentRecord} by id; the record itself lives in the
 * project. That keeps the canvas a view over the mod, so the same content can appear on several
 * boards and imported content that has no natural place is still representable.
 */
public final class CanvasNode {
    public static final String KIND_CONTENT = "content";
    public static final String KIND_NOTE = "note";
    public static final String KIND_GROUP = "group";

    public String id;
    public String kind = KIND_CONTENT;
    public String contentId;
    public double x;
    public double y;
    public double w = 260;
    public double h = 130;
    /** Note body or group label. */
    public String text = "";
    public String color = "";
}
