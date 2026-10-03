package dev.modmaker.core.model;

import java.util.ArrayList;
import java.util.List;

/** A board is one JSON Canvas file: nodes plus the reference edges between them. */
public final class Board {
    public String id;
    public String name = "Main";
    /** File name inside the project's boards/ folder. */
    public String file = "main.canvas";
    public List<CanvasNode> nodes = new ArrayList<>();
    public List<CanvasEdge> edges = new ArrayList<>();
    public Viewport viewport = new Viewport();

    /** Not part of JSON Canvas; kept in the project sidecar so a board reopens where it was left. */
    public static final class Viewport {
        public double x;
        public double y;
        public double zoom = 1;
    }
}
