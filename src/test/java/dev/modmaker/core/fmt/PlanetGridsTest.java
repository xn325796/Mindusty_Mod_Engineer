package dev.modmaker.core.fmt;

import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PlanetGrid port must reproduce the game's grid exactly: sector indices in SectorPreset JSON
 * are indexes into it, so counts, normalization and topology have to line up.
 */
class PlanetGridsTest {

    @Test
    void countsFollowTheGeodesicFormulas() {
        int[][] expected = {
            {12, 20, 30},     // size 0: icosahedron
            {32, 60, 90},     // size 1
            {92, 180, 270},   // size 2: erekir
            {272, 540, 810},  // size 3: serpulo
        };
        for (int size = 0; size <= 3; size++) {
            PlanetGrids.Grid grid = PlanetGrids.create(size);
            assertEquals(expected[size][0], grid.tiles.length, "tiles at size " + size);
            assertEquals(expected[size][1], grid.corners.length, "corners at size " + size);
            assertEquals(expected[size][2], PlanetGrids.edgeCount(size), "edges at size " + size);
            // Euler characteristic of a sphere: V - E + F = 2
            assertEquals(2, grid.tiles.length - PlanetGrids.edgeCount(size) + grid.corners.length,
                "Euler characteristic at size " + size);
        }
    }

    @Test
    void allVectorsAreUnitLength() {
        PlanetGrids.Grid grid = PlanetGrids.create(3);
        for (var tile : grid.tiles) {
            assertTrue(Math.abs(length(tile.v) - 1) < 1e-4, "tile " + tile.id + " not normalized");
            for (var corner : tile.corners) {
                assertTrue(corner != null, "tile " + tile.id + " has an unassigned corner");
                assertTrue(Math.abs(length(corner.v) - 1) < 1e-4);
            }
        }
    }

    @Test
    void everyCornerIsSharedByExactlyThreeTiles() {
        PlanetGrids.Grid grid = PlanetGrids.create(2);
        java.util.Map<Integer, Integer> uses = new java.util.HashMap<>();
        for (var tile : grid.tiles) {
            assertEquals(tile.edgeCount, tile.corners.length, "tile " + tile.id);
            for (var corner : tile.corners) {
                uses.merge(corner.id, 1, Integer::sum);
            }
        }
        for (var corner : grid.corners) {
            assertEquals(3, uses.get(corner.id), "corner " + corner.id + " use count");
        }
    }

    @Test
    void constructionIsDeterministic() {
        PlanetGrids.Grid a = PlanetGrids.create(3);
        PlanetGrids.Grid b = PlanetGrids.create(3);
        for (int i = 0; i < a.tiles.length; i++) {
            assertEquals(a.tiles[i].v.x, b.tiles[i].v.x, 1e-9);
            assertEquals(a.tiles[i].v.y, b.tiles[i].v.y, 1e-9);
            assertEquals(a.tiles[i].v.z, b.tiles[i].v.z, 1e-9);
        }
    }

    @Test
    void capacityMatchesTheGamesSectorList() {
        assertEquals(1, PlanetGrids.capacity(0), "gridless planets have only the degenerate slot");
        assertEquals(93, PlanetGrids.capacity(2), "erekir-style grid: 92 tiles + 1 slot");
        assertEquals(273, PlanetGrids.capacity(3), "serpulo-style grid: 272 tiles + 1 slot");
        assertEquals(93, PlanetGrids.capacity(PlanetGrids.VANILLA_SIZES.get("erekir")));
    }

    @Test
    void sectorPresetsGroupByPlanetAndIndex() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ContentRecord planet = new ContentRecord();
        planet.id = "p";
        planet.type = ContentType.planet;
        planet.name = "homeworld";
        planet.ext = "json";
        planet.fields.put("sectorSize", new JsonPrimitive(2));
        project.contents.add(planet);

        ContentRecord bare = sector("bare-sector", "homeworld", 5);
        ContentRecord prefixed = sector("prefixed-sector", "my-mod-homeworld", 40);
        ContentRecord negative = sector("negative-sector", "homeworld", -3);
        ContentRecord elsewhere = sector("elsewhere", "serpulo", 7);
        ContentRecord orphan = sector("orphan", null, 9);
        project.contents.add(bare);
        project.contents.add(prefixed);
        project.contents.add(negative);
        project.contents.add(elsewhere);
        project.contents.add(orphan);

        Map<Integer, List<ContentRecord>> map =
            PlanetGrids.sectorsByIndex(project, "homeworld", 2);
        assertEquals(3, map.size(), map.toString());
        assertEquals("bare-sector", map.get(5).get(0).name);
        assertEquals("prefixed-sector", map.get(40).get(0).name);
        // floorMod: -3 mod 93 = 90
        assertEquals("negative-sector", map.get(90).get(0).name);
        assertTrue(map.values().stream().flatMap(List::stream)
            .noneMatch(record -> record.name.equals("elsewhere") || record.name.equals("orphan")));
    }

    private static ContentRecord sector(String name, String planet, Integer index) {
        ContentRecord record = new ContentRecord();
        record.id = "id-" + name;
        record.type = ContentType.sector;
        record.name = name;
        record.ext = "json";
        if (planet != null) {
            record.fields.put("planet", new JsonPrimitive(planet));
        }
        if (index != null) {
            record.fields.put("sector", new JsonPrimitive(index));
        }
        return record;
    }

    private static double length(PlanetGrids.Vec3 v) {
        return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
    }
}
