package dev.modmaker.core.fmt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixed mesh field lists must mirror ContentParser.parseMesh exactly. */
class MeshTypesTest {

    @Test
    void exactlyTheParseableTypes() {
        assertEquals(java.util.Set.of("NoiseMesh", "SunMesh", "HexSkyMesh", "MultiMesh", "MatMesh"),
            MeshTypes.PARSEABLE);
        assertNull(MeshTypes.spec("HexMesh"), "serpulo's HexMesh is not JSON-parseable");
        assertNull(MeshTypes.spec("ShaderSphereMesh"));
    }

    @Test
    void noiseMeshFieldsMatchTheConstructor() {
        var spec = MeshTypes.spec("NoiseMesh");
        assertEquals(List.of("seed", "divisions", "radius", "octaves", "persistence", "scale",
                "mag", "color1", "color2", "colorOct", "colorPersistence", "colorScale",
                "colorThreshold"),
            spec.fields().stream().map(MeshTypes.Field::name).toList());
    }

    @Test
    void skyMeshAndSunMeshFields() {
        assertEquals(List.of("seed", "speed", "radius", "divisions", "color", "octaves",
                "persistence", "scale", "thresh"),
            MeshTypes.spec("HexSkyMesh").fields().stream()
                .map(MeshTypes.Field::name).toList());
        assertEquals(List.of("divisions", "octaves", "persistence", "scl", "pow", "mag",
                "colorScale", "colors"),
            MeshTypes.spec("SunMesh").fields().stream()
                .map(MeshTypes.Field::name).toList());
    }
}
