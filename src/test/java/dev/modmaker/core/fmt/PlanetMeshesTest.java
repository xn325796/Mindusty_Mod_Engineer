package dev.modmaker.core.fmt;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.noise.SimplexNoise;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mesh construction mirrors MeshBuilder.buildHex + the game's mesh classes. */
class PlanetMeshesTest {

    private static PlanetGrids.Vec3 vec(double x, double y, double z) {
        return new PlanetGrids.Vec3((float) x, (float) y, (float) z);
    }

    @Test
    void noiseMeshHeightMatchesTheGameFormula() {
        var mesher = PlanetMeshes.noiseMesh(2, 3, 0.5f, 1f, 0.08f,
            PlanetMeshes.Rgb.of("3a6b8c"), PlanetMeshes.Rgb.of("5e8c4a"), 1, 0.5f, 1f, 0.5f);
        var position = vec(0.31, -0.52, 0.79);
        // Locks the FORMULA (seed offset 7, +5 position offsets, * mag) against the ported noise;
        // the noise implementation itself is verified against arc in SimplexNoiseTest.
        float expected = SimplexNoise.noise3d(7 + 2, 3, 0.5f, 1f,
            5 + position.x, 5 + position.y, 5 + position.z) * 0.08f;
        assertEquals(expected, mesher.height(position), 1e-6);
    }

    @Test
    void noiseMeshColorIsThresholdedBetweenTwoColors() {
        var color1 = PlanetMeshes.Rgb.of("3a6b8c");
        var color2 = PlanetMeshes.Rgb.of("5e8c4a");
        var mesher = PlanetMeshes.noiseMesh(2, 3, 0.5f, 1f, 0.08f, color1, color2, 1, 0.5f, 1f, 0.5f);
        // every tile color must be exactly one of the two palette colors
        PlanetGrids.Grid grid = PlanetGrids.create(2);
        for (var tile : grid.tiles) {
            RgbAssert.assertOneOf(mesher.color(tile.v), color1, color2);
        }
    }

    @Test
    void hexSkyCloudsSkipTilesAboveTheThreshold() {
        var mesher = PlanetMeshes.hexSkyMesh(11, 2, 0.45f, 0.9f, 0.38f, PlanetMeshes.Rgb.of("684eb9bf"));
        PlanetGrids.Grid grid = PlanetGrids.create(2);
        int drawn = 0;
        int skipped = 0;
        for (var tile : grid.tiles) {
            if (mesher.skip(tile.v)) {
                skipped++;
            } else {
                drawn++;
                assertEquals(1f, mesher.height(tile.v), 1e-6, "cloud tiles float above the surface");
            }
        }
        assertTrue(drawn > 0 && skipped > 0, "clouds should cover part of the planet");
        assertEquals(92, drawn + skipped);
    }

    @Test
    void buildModelScalesCornersByHeightAndRadius() {
        var mesher = PlanetMeshes.singleColorMesh(0, 1, 0.5f, 1f, 0f, PlanetMeshes.Rgb.of("ffffff"));
        // mag = 0 -> all heights are exactly radius, so every scaled corner sits on the unit sphere
        PlanetMeshes.MeshModel model = PlanetMeshes.buildModel(2, 1f, mesher, 0.2f);
        assertEquals(92, model.tileCorners.size());
        for (double[] corners : model.tileCorners) {
            for (int k = 0; k < corners.length; k += 3) {
                double length = Math.sqrt(corners[k] * corners[k] + corners[k + 1] * corners[k + 1]
                    + corners[k + 2] * corners[k + 2]);
                assertEquals(1f, length, 1e-5, "corners scaled by radius only stay on the sphere");
            }
        }
    }

    @Test
    void configParsingFallsBackLikeParseMesh() {
        JsonObject mesh = new JsonObject();
        mesh.addProperty("type", "NoiseMesh");
        mesh.addProperty("seed", 2);
        // color1/color2 absent -> both fall back to "color"
        mesh.addProperty("color", "3a6b8c");
        var mesher = PlanetMeshes.mesherFromConfig(mesh);
        var grid = PlanetGrids.create(1);
        var expected = PlanetMeshes.Rgb.of("3a6b8c");
        RgbAssert.assertOneOf(mesher.color(grid.tiles[3].v), expected, expected);
    }

    @Test
    void unparseableMeshTypesFallBackInsteadOfCrashing() {
        JsonObject mesh = new JsonObject();
        mesh.addProperty("type", "HexMesh");
        var mesher = PlanetMeshes.mesherFromConfig(mesh);
        assertNotNull(mesher);
        var grid = PlanetGrids.create(1);
        assertTrue(Double.isFinite(mesher.height(grid.tiles[0].v)));
    }

    @Test
    void cloudLayersComeFromMultiMeshChildren() {
        JsonObject cloud = Json.parseObject("""
            {"type": "MultiMesh", "meshes": [
                {"type": "HexSkyMesh", "seed": 11, "radius": 0.13, "color": "684eb9bf"},
                {"type": "HexSkyMesh", "seed": 1, "radius": 0.16, "color": "b3a3e4bf"}
            ]}
            """);
        List<PlanetMeshes.Layer> layers = PlanetMeshes.cloudLayersFromConfig(cloud);
        assertEquals(2, layers.size());
        assertEquals(0.13f, layers.get(0).intensity(), 1e-6);
        assertEquals(0.16f, layers.get(1).intensity(), 1e-6);
    }

    /** Tiny assertion helper: color equals one of the candidates. */
    private static final class RgbAssert {
        private static void assertOneOf(PlanetMeshes.Rgb actual, PlanetMeshes.Rgb... candidates) {
            for (PlanetMeshes.Rgb candidate : candidates) {
                if (Math.abs(actual.r() - candidate.r()) < 1e-6
                    && Math.abs(actual.g() - candidate.g()) < 1e-6
                    && Math.abs(actual.b() - candidate.b()) < 1e-6) {
                    return;
                }
            }
            throw new AssertionError("color " + actual + " matches no candidate");
        }
    }
}
