package dev.modmaker.core.fmt;

import dev.modmaker.core.schema.FieldType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mesh types the game's JSON parser accepts (ContentParser.parseMesh) and the exact fields each
 * mesh constructor reads. A planet's "mesh" and "cloudMesh" are edited against these fixed lists -
 * keys outside them are ignored by the game or crash the parser with "Unknown mesh type".
 *
 * <p>Notably, serpulo's own HexMesh is NOT parseable from JSON, so it must never be offered.
 */
public final class MeshTypes {

    /** One editable mesh field: its JSON key and the widget that edits it. */
    public record Field(String name, FieldType type) {
    }

    /** One mesh type and its fixed field list (MultiMesh/MatMesh nest children in the editor). */
    public record Spec(String type, List<Field> fields) {
    }

    public static final List<Spec> ALL = List.of(
        new Spec("NoiseMesh", List.of(
            new Field("seed", FieldType.number),
            new Field("divisions", FieldType.number),
            new Field("radius", FieldType.number),
            new Field("octaves", FieldType.number),
            new Field("persistence", FieldType.number),
            new Field("scale", FieldType.number),
            new Field("mag", FieldType.number),
            new Field("color1", FieldType.color),
            new Field("color2", FieldType.color),
            new Field("colorOct", FieldType.number),
            new Field("colorPersistence", FieldType.number),
            new Field("colorScale", FieldType.number),
            new Field("colorThreshold", FieldType.number))),
        new Spec("SunMesh", List.of(
            new Field("divisions", FieldType.number),
            new Field("octaves", FieldType.number),
            new Field("persistence", FieldType.number),
            new Field("scl", FieldType.number),
            new Field("pow", FieldType.number),
            new Field("mag", FieldType.number),
            new Field("colorScale", FieldType.number),
            new Field("colors", FieldType.textArray))),
        new Spec("HexSkyMesh", List.of(
            new Field("seed", FieldType.number),
            new Field("speed", FieldType.number),
            new Field("radius", FieldType.number),
            new Field("divisions", FieldType.number),
            new Field("color", FieldType.color),
            new Field("octaves", FieldType.number),
            new Field("persistence", FieldType.number),
            new Field("scale", FieldType.number),
            new Field("thresh", FieldType.number))),
        // MultiMesh: "meshes" is a list of child meshes (editor builds them recursively).
        new Spec("MultiMesh", List.of()),
        // MatMesh: "mesh" is a nested mesh, "mat" a raw Mat3D matrix (editor special-cases both).
        new Spec("MatMesh", List.of()));

    /** The type names parseMesh accepts; anything else throws "Unknown mesh type". */
    public static final Set<String> PARSEABLE =
        ALL.stream().map(Spec::type).collect(Collectors.toUnmodifiableSet());

    /** The spec for a mesh type name, or null when the game cannot parse that type. */
    public static Spec spec(String type) {
        for (Spec spec : ALL) {
            if (spec.type().equals(type)) {
                return spec;
            }
        }
        return null;
    }
}
