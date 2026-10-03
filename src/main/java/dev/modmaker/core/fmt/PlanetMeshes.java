package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.noise.SimplexNoise;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Planet surface/cloud mesh construction, mirroring MeshBuilder.buildHex + the game's mesh classes:
 * every planet mesh is a set of grid tiles whose corners are scaled by (1 + height * intensity) *
 * radius, colored per tile by the mesher, optionally skipped (clouds). Simplex noise calls match the
 * game exactly (NoiseMesh uses offsets 5 + position with seeds 7 + seed and 8 + seed).
 */
public final class PlanetMeshes {

    private PlanetMeshes() {
    }

    /** Simple RGBA color used by the mesher math. */
    public record Rgb(float r, float g, float b, float a) {
        public static Rgb of(String hex) {
            String text = hex.startsWith("#") ? hex.substring(1) : hex;
            long value = Long.parseUnsignedLong(text.length() == 6 ? text + "ff" : text, 16);
            return new Rgb(
                (float) ((value >> 24) & 0xFF) / 255f,
                (float) ((value >> 16) & 0xFF) / 255f,
                (float) ((value >> 8) & 0xFF) / 255f,
                (float) (value & 0xFF) / 255f);
        }
    }

    /** Per-tile mesher callbacks, mirroring the game's HexMesher interface. */
    public interface Mesher {
        float height(PlanetGrids.Vec3 position);

        Rgb color(PlanetGrids.Vec3 position);

        default boolean skip(PlanetGrids.Vec3 position) {
            return false;
        }
    }

    /** The rendered geometry of one mesh layer: tile polygons + face colors. */
    public static final class MeshModel {
        public final int sectorSize;
        public final float radius;
        /** Per tile: corners as flat [x,y,z, x,y,z, ...] (already scaled by height * radius). */
        public final List<double[]> tileCorners = new ArrayList<>();
        public final List<Rgb> tileColors = new ArrayList<>();
        public final List<Boolean> tileSkipped = new ArrayList<>();
        public final List<Integer> tileIndexes = new ArrayList<>();

        MeshModel(int sectorSize, float radius) {
            this.sectorSize = sectorSize;
            this.radius = radius;
        }
    }

    // --- mesher builders (defaults mirror ContentParser.parseMesh) ------------------------------

    public static Mesher noiseMesh(int seed, int octaves, float persistence, float scale, float mag,
        Rgb color1, Rgb color2, int colorOct, float colorPersistence, float colorScale,
        float colorThreshold) {
        return new Mesher() {
            @Override
            public float height(PlanetGrids.Vec3 position) {
                return SimplexNoise.noise3d(7 + seed, octaves, persistence, scale,
                    5f + position.x, 5f + position.y, 5f + position.z) * mag;
            }

            @Override
            public Rgb color(PlanetGrids.Vec3 position) {
                double mask = SimplexNoise.noise3d(8 + seed, colorOct, colorPersistence, colorScale,
                    5f + position.x, 5f + position.y, 5f + position.z);
                return mask > colorThreshold ? color2 : color1;
            }
        };
    }

    public static Mesher singleColorMesh(int seed, int octaves, float persistence, float scale,
        float mag, Rgb color) {
        return noiseMesh(seed, octaves, persistence, scale, mag, color, color, 1, 0.5f, 1, 0.5f);
    }

    public static Mesher sunMesh(double octaves, double persistence, double scl, double pow,
        double mag, float colorScale, List<Rgb> colors) {
        return new Mesher() {
            @Override
            public float height(PlanetGrids.Vec3 position) {
                return 0;
            }

            @Override
            public Rgb color(PlanetGrids.Vec3 position) {
                double height = Math.pow(
                    SimplexNoise.noise3d(0, octaves, persistence, scl,
                        position.x, position.y, position.z), pow) * mag;
                int index = Math.max(0, Math.min(colors.size() - 1,
                    (int) (height * colors.size())));
                Rgb color = colors.get(index);
                return new Rgb(color.r() * colorScale, color.g() * colorScale,
                    color.b() * colorScale, color.a());
            }
        };
    }

    public static Mesher hexSkyMesh(int seed, int octaves, float persistence, float scl,
        float thresh, Rgb color) {
        return new Mesher() {
            @Override
            public float height(PlanetGrids.Vec3 position) {
                return 1f;
            }

            @Override
            public Rgb color(PlanetGrids.Vec3 position) {
                return color;
            }

            @Override
            public boolean skip(PlanetGrids.Vec3 position) {
                return SimplexNoise.noise3d(7 + seed, octaves, persistence, scl,
                    position.x, position.y * 3f, position.z) >= thresh;
            }
        };
    }

    // --- config parsing (defaults mirror ContentParser.parseMesh) -------------------------------

    public static Rgb colorOr(JsonElement element, String fallback) {
        if (element != null && element.isJsonPrimitive()) {
            try {
                return Rgb.of(element.getAsString());
            } catch (NumberFormatException ignored) {
                return Rgb.of(fallback);
            }
        }
        return Rgb.of(fallback);
    }

    /** Builds the surface mesher from a planet's "mesh" object (any parseable type; unknown falls back). */
    public static Mesher mesherFromConfig(JsonObject mesh) {
        String type = stringOr(mesh, "type", "NoiseMesh");
        switch (type) {
            case "SunMesh": {
                List<Rgb> colors = new ArrayList<>();
                JsonElement raw = mesh.get("colors");
                if (raw != null && raw.isJsonArray()) {
                    for (JsonElement element : raw.getAsJsonArray()) {
                        colors.add(Rgb.of(element.getAsString()));
                    }
                }
                if (colors.isEmpty()) {
                    colors.add(Rgb.of("ff7a38"));
                }
                return sunMesh(intOr(mesh, "octaves", 1), numOr(mesh, "persistence", 0.5f),
                    numOr(mesh, "scl", 1f), numOr(mesh, "pow", 1f), numOr(mesh, "mag", 0.5f),
                    (float) numOr(mesh, "colorScale", 1f), colors);
            }
            case "HexSkyMesh":
                return hexSkyMesh(intOr(mesh, "seed", 0), intOr(mesh, "octaves", 1),
                    (float) numOr(mesh, "persistence", 0.5f), (float) numOr(mesh, "scale", 1f),
                    (float) numOr(mesh, "thresh", 0.5f),
                    colorOr(mesh.get("color"), "ffffff"));
            case "MatMesh": {
                JsonElement nested = mesh.get("mesh");
                if (nested != null && nested.isJsonObject()) {
                    return mesherFromConfig(nested.getAsJsonObject());
                }
                return defaultMesher();
            }
            case "MultiMesh": {
                JsonElement children = mesh.get("meshes");
                if (children != null && children.isJsonArray() && children.getAsJsonArray().size() > 0) {
                    return mesherFromConfig(children.getAsJsonArray().get(0).getAsJsonObject());
                }
                return defaultMesher();
            }
            default:
                if (!type.equals("NoiseMesh")) {
                    return defaultMesher(); // unparseable types (e.g. HexMesh) fall back
                }
                return noiseMesh(intOr(mesh, "seed", 0), intOr(mesh, "divisions", 1) > 0 ? intOr(mesh, "octaves", 1) : 1,
                    (float) numOr(mesh, "persistence", 0.5f), (float) numOr(mesh, "scale", 1f),
                    (float) numOr(mesh, "mag", 0.5f),
                    colorOr(mesh.get("color1"), stringOr(mesh, "color", "ffffff")),
                    colorOr(mesh.get("color2"), stringOr(mesh, "color", "ffffff")),
                    intOr(mesh, "colorOct", 1), (float) numOr(mesh, "colorPersistence", 0.5f),
                    (float) numOr(mesh, "colorScale", 1f), (float) numOr(mesh, "colorThreshold", 0.5f));
        }
    }

    public static Mesher defaultMesher() {
        return singleColorMesh(0, 1, 0.5f, 1f, 0.5f, Rgb.of("ffffff"));
    }

    /** Cloud layers from a "cloudMesh" object: MultiMesh children, or the mesh itself. */
    public static List<Layer> cloudLayersFromConfig(JsonObject cloud) {
        List<Layer> layers = new ArrayList<>();
        if (cloud == null) {
            return layers;
        }
        String type = stringOr(cloud, "type", "NoiseMesh");
        if (type.equals("MultiMesh") && cloud.has("meshes") && cloud.get("meshes").isJsonArray()) {
            for (JsonElement element : cloud.getAsJsonArray("meshes")) {
                if (element != null && element.isJsonObject()) {
                    layers.add(layerFrom(element.getAsJsonObject()));
                }
            }
        } else {
            layers.add(layerFrom(cloud));
        }
        return layers;
    }

    private static Layer layerFrom(JsonObject config) {
        String type = stringOr(config, "type", "NoiseMesh");
        // Clouds in practice are HexSkyMesh: intensity comes from its "radius" parameter.
        float intensity = (float) numOr(config, "radius", 1f);
        Mesher mesher = "HexSkyMesh".equals(type)
            ? hexSkyMesh(intOr(config, "seed", 0), intOr(config, "octaves", 1),
                (float) numOr(config, "persistence", 0.5f), (float) numOr(config, "scale", 1f),
                (float) numOr(config, "thresh", 0.5f), colorOr(config.get("color"), "ffffff"))
            : mesherFromConfig(config);
        return new Layer(mesher, intensity);
    }

    /** One rendered mesh layer: a mesher plus its buildHex intensity. */
    public record Layer(Mesher mesher, float intensity) {
    }

    // --- model building -------------------------------------------------------------------------

    /** Builds tile geometry for one layer, mirroring MeshBuilder.buildHex. */
    public static MeshModel buildModel(int sectorSize, float radius, Mesher mesher, float intensity) {
        MeshModel model = new MeshModel(sectorSize, radius);
        if (sectorSize <= 0) {
            return model;
        }
        PlanetGrids.Grid grid = PlanetGrids.create(sectorSize);
        float[] heights = new float[grid.corners.length];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = (1f + mesher.height(grid.corners[i].v) * intensity) * radius;
        }
        for (int i = 0; i < grid.tiles.length; i++) {
            PlanetGrids.Tile tile = grid.tiles[i];
            if (mesher.skip(tile.v)) {
                model.tileSkipped.add(true);
                model.tileColors.add(null);
                model.tileCorners.add(new double[0]);
                model.tileIndexes.add(i);
                continue;
            }
            model.tileSkipped.add(false);
            model.tileColors.add(mesher.color(tile.v));
            double[] corners = new double[tile.corners.length * 3];
            for (int k = 0; k < tile.corners.length; k++) {
                PlanetGrids.Corner corner = tile.corners[k];
                float height = heights[corner.id];
                corners[k * 3] = corner.v.x * height;
                corners[k * 3 + 1] = corner.v.y * height;
                corners[k * 3 + 2] = corner.v.z * height;
            }
            model.tileCorners.add(corners);
            model.tileIndexes.add(i);
        }
        return model;
    }

    // --- small parsers --------------------------------------------------------------------------

    public static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    public static int intOr(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        if (value != null && value.isJsonPrimitive()) {
            try {
                return value.getAsInt();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static double numOr(JsonObject object, String key, double fallback) {
        JsonElement value = object == null ? null : object.get(key);
        if (value != null && value.isJsonPrimitive()) {
            try {
                return value.getAsDouble();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Locale-independent parse guard used by callers formatting noise inputs. */
    public static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
