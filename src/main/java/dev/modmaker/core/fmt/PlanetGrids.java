package dev.modmaker.core.fmt;

import com.google.gson.JsonElement;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Faithful port of mindustry.graphics.g3d.PlanetGrid: the geodesic icosphere grid whose tiles are a
 * planet's sectors. Sector indices in SectorPreset JSON are indexes into this grid, so the port
 * must reproduce the source exactly - same constants, same subdivision order, same tile numbering.
 *
 * <p>Also carries the sector-preset bookkeeping the planet workspace needs: how many sectors a
 * planet can hold ({@code 10*3^size + 2} tiles, plus the one degenerate slot the game appends) and
 * which indexes the project's sector records occupy.
 */
public final class PlanetGrids {

    private PlanetGrids() {
    }

    /** Sizes of the vanilla planets that mod sectors commonly reference (Planets.java). */
    public static final Map<String, Integer> VANILLA_SIZES =
        Map.of("serpulo", 3, "erekir", 2, "tantros", 2);

    private static final float PX = -0.525731112119133606f;
    private static final float PZ = -0.850650808352039932f;

    private static final float[][] I_TILES = {
        {-PX, 0, PZ}, {PX, 0, PZ}, {-PX, 0, -PZ}, {PX, 0, -PZ},
        {0, PZ, PX}, {0, PZ, -PX}, {0, -PZ, PX}, {0, -PZ, -PX},
        {PZ, PX, 0}, {-PZ, PX, 0}, {PZ, -PX, 0}, {-PZ, -PX, 0}
    };
    private static final int[][] I_TILES_P = {
        {9, 4, 1, 6, 11}, {4, 8, 10, 6, 0}, {11, 7, 3, 5, 9}, {2, 7, 10, 8, 5},
        {9, 5, 8, 1, 0}, {2, 3, 8, 4, 9}, {0, 1, 10, 7, 11}, {11, 6, 10, 3, 2},
        {5, 3, 10, 1, 4}, {2, 5, 4, 0, 11}, {3, 7, 6, 1, 8}, {7, 2, 9, 0, 6}
    };

    public static int tileCount(int size) {
        return 10 * pow3(size) + 2;
    }

    public static int cornerCount(int size) {
        return 20 * pow3(size);
    }

    public static int edgeCount(int size) {
        return 30 * pow3(size);
    }

    private static int pow3(int size) {
        int result = 1;
        for (int i = 0; i < size; i++) {
            result *= 3;
        }
        return result;
    }

    // --- grid construction (ported) -------------------------------------------------------------

    public static final class Vec3 {
        public float x, y, z;

        public Vec3() {
        }

        public Vec3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Vec3(Vec3 other) {
            set(other);
        }

        public Vec3 set(Vec3 other) {
            x = other.x;
            y = other.y;
            z = other.z;
            return this;
        }

        public Vec3 add(Vec3 other) {
            x += other.x;
            y += other.y;
            z += other.z;
            return this;
        }

        public Vec3 nor() {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length != 0) {
                x /= length;
                y /= length;
                z /= length;
            }
            return this;
        }
    }

    public static final class Tile {
        public final int id;
        public final int edgeCount;
        public final Tile[] tiles;
        public final Corner[] corners;
        public final Vec3 v = new Vec3();

        Tile(int id, int edgeCount) {
            this.id = id;
            this.edgeCount = edgeCount;
            tiles = new Tile[edgeCount];
            corners = new Corner[edgeCount];
        }
    }

    public static final class Corner {
        public final int id;
        public final Tile[] tiles = new Tile[3];
        public final Corner[] corners = new Corner[3];
        public final Vec3 v = new Vec3();

        Corner(int id) {
            this.id = id;
        }
    }

    public static final class Grid {
        public final int size;
        public final Tile[] tiles;
        public final Corner[] corners;

        private Grid(int size) {
            this.size = size;
            tiles = new Tile[tileCount(size)];
            for (int i = 0; i < tiles.length; i++) {
                tiles[i] = new Tile(i, i < 12 ? 5 : 6);
            }
            corners = new Corner[cornerCount(size)];
            for (int i = 0; i < corners.length; i++) {
                corners[i] = new Corner(i);
            }
        }
    }

    public static Grid create(int size) {
        if (size <= 0) {
            return initialGrid();
        }
        return subdividedGrid(create(size - 1));
    }

    private static Grid initialGrid() {
        Grid grid = new Grid(0);

        for (Tile t : grid.tiles) {
            t.v.set(new Vec3(I_TILES[t.id][0], I_TILES[t.id][1], I_TILES[t.id][2]));
            for (int k = 0; k < 5; k++) {
                t.tiles[k] = grid.tiles[I_TILES_P[t.id][k]];
            }
        }
        for (int i = 0; i < 5; i++) {
            addCorner(i, grid, 0, I_TILES_P[0][(i + 4) % 5], I_TILES_P[0][i]);
        }
        for (int i = 0; i < 5; i++) {
            addCorner(i + 5, grid, 3, I_TILES_P[3][(i + 4) % 5], I_TILES_P[3][i]);
        }
        addCorner(10, grid, 10, 1, 8);
        addCorner(11, grid, 1, 10, 6);
        addCorner(12, grid, 6, 10, 7);
        addCorner(13, grid, 6, 7, 11);
        addCorner(14, grid, 11, 7, 2);
        addCorner(15, grid, 11, 2, 9);
        addCorner(16, grid, 9, 2, 5);
        addCorner(17, grid, 9, 5, 4);
        addCorner(18, grid, 4, 5, 8);
        addCorner(19, grid, 4, 8, 1);

        for (Corner c : grid.corners) {
            for (int k = 0; k < 3; k++) {
                c.corners[k] = c.tiles[k].corners[(pos(c.tiles[k], c) + 1) % 5];
            }
        }
        int nextEdge = 0;
        for (Tile t : grid.tiles) {
            for (int k = 0; k < 5; k++) {
                // edges exist in this port only implicitly through tiles/corners; the source's
                // edge bookkeeping has no effect on tile geometry or indices.
                if (t.tiles[k] == null) {
                    throw new IllegalStateException("unconnected grid tile " + t.id);
                }
            }
            nextEdge++;
        }
        return grid;
    }

    private static Grid subdividedGrid(Grid prev) {
        Grid grid = new Grid(prev.size + 1);

        int prevTiles = prev.tiles.length;
        int prevCorners = prev.corners.length;

        for (int i = 0; i < prevTiles; i++) {
            grid.tiles[i].v.set(prev.tiles[i].v);
            for (int k = 0; k < grid.tiles[i].edgeCount; k++) {
                grid.tiles[i].tiles[k] = grid.tiles[prev.tiles[i].corners[k].id + prevTiles];
            }
        }
        for (int i = 0; i < prevCorners; i++) {
            grid.tiles[i + prevTiles].v.set(prev.corners[i].v);
            for (int k = 0; k < 3; k++) {
                grid.tiles[i + prevTiles].tiles[2 * k] =
                    grid.tiles[prev.corners[i].corners[k].id + prevTiles];
                grid.tiles[i + prevTiles].tiles[2 * k + 1] =
                    grid.tiles[prev.corners[i].tiles[k].id];
            }
        }
        int nextCorner = 0;
        for (Tile n : prev.tiles) {
            Tile t = grid.tiles[n.id];
            for (int k = 0; k < t.edgeCount; k++) {
                addCorner(nextCorner, grid, t.id,
                    t.tiles[(k + t.edgeCount - 1) % t.edgeCount].id, t.tiles[k].id);
                nextCorner++;
            }
        }
        for (Corner c : grid.corners) {
            for (int k = 0; k < 3; k++) {
                c.corners[k] = c.tiles[k].corners[(pos(c.tiles[k], c) + 1) % (c.tiles[k].edgeCount)];
            }
        }
        return grid;
    }

    static void addCorner(int id, Grid grid, int t1, int t2, int t3) {
        Corner c = grid.corners[id];
        Tile[] t = {grid.tiles[t1], grid.tiles[t2], grid.tiles[t3]};
        c.v.set(t[0].v).add(t[1].v).add(t[2].v).nor();
        for (int i = 0; i < 3; i++) {
            t[i].corners[pos(t[i], t[(i + 2) % 3])] = c;
            c.tiles[i] = t[i];
        }
    }

    static int pos(Tile t, Tile n) {
        for (int i = 0; i < t.edgeCount; i++) {
            if (t.tiles[i] == n) {
                return i;
            }
        }
        return -1;
    }

    static int pos(Tile t, Corner c) {
        for (int i = 0; i < t.edgeCount; i++) {
            if (t.corners[i] == c) {
                return i;
            }
        }
        return -1;
    }

    // --- planet bookkeeping ---------------------------------------------------------------------

    /**
     * How many sectors a planet with this {@code sectorSize} can address:
     * {@code 10*3^size + 2} grid tiles, plus the one degenerate slot the game always appends.
     * A planet without a grid (sectorSize = 0) addresses exactly one degenerate sector.
     */
    public static int capacity(int sectorSize) {
        return sectorSize <= 0 ? 1 : tileCount(sectorSize) + 1;
    }

    /** The planet a sector record declares, or the game's default (serpulo). */
    public static String sectorPlanet(ContentRecord sector) {
        JsonElement value = sector.fields.get("planet");
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            return "serpulo";
        }
        return value.getAsString();
    }

    public static int sectorIndex(ContentRecord sector, int capacity) {
        int index = 0;
        JsonElement value = sector.fields.get("sector");
        if (value != null && value.isJsonPrimitive()) {
            try {
                index = value.getAsInt();
            } catch (NumberFormatException ignored) {
                index = 0;
            }
        }
        // The game uses plain %, which yields negative indexes for negative sector numbers; floorMod
        // keeps the preview well-defined without changing any positive-index result.
        return Math.floorMod(index, capacity);
    }

    /**
     * Groups the project's sector records by the grid index they occupy on the named planet.
     * A sector matches when its {@code planet} field names this planet (bare or prefixed name);
     * sectors pointing elsewhere (including other mod planets) are not part of this planet's map.
     */
    public static Map<Integer, List<ContentRecord>> sectorsByIndex(ModProject project,
        String planetBareName, int sectorSize) {
        Map<Integer, List<ContentRecord>> out = new LinkedHashMap<>();
        int capacity = capacity(sectorSize);
        for (ContentRecord sector : project.contents) {
            if (sector.type != ContentType.sector || sector.isInline()) {
                continue;
            }
            String target = sectorPlanet(sector);
            String bare = target.startsWith(project.internalName() + "-")
                ? target.substring(project.internalName().length() + 1)
                : target;
            if (!bare.equals(planetBareName)) {
                continue;
            }
            int index = sectorIndex(sector, capacity);
            out.computeIfAbsent(index, ignored -> new ArrayList<>()).add(sector);
        }
        return out;
    }

    public static int intField(ContentRecord record, String field, int fallback) {
        JsonElement value = record.fields.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** True when the name refers to a known vanilla planet with a campaign grid. */
    public static boolean isVanillaPlanet(String name) {
        return VANILLA_SIZES.containsKey(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Unused strictness helper kept for tests. */
    public static Set<String> vanillaPlanetNames() {
        return new LinkedHashSet<>(VANILLA_SIZES.keySet());
    }
}
