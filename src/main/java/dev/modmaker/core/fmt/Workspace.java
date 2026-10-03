package dev.modmaker.core.fmt;

/**
 * Which main-area workspace a content type is edited in. The node canvas stays the flow editor for
 * blocks and units (and everything that participates in reference edges); simple assets get a
 * management table; planets and sectors get an arrangement view.
 */
public enum Workspace {
    /** Flow editing on the node canvas: blocks, units, and everything reference-related. */
    canvas,
    /** Asset management table: items and liquids. */
    assets,
    /** Arrangement view: planets and their sectors. */
    cosmos;

    public static Workspace of(ContentType type) {
        return switch (type) {
            case item, liquid -> assets;
            case planet, sector -> cosmos;
            default -> canvas;
        };
    }
}
