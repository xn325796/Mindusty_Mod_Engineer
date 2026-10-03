package dev.modmaker.core.fmt;

import com.google.gson.JsonObject;
import dev.modmaker.core.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The serpulo planet template: its values mirror vanilla Planets.java, and every mesh type it uses
 * must be one the game's JSON parser actually accepts.
 */
class PlanetTemplatesTest {

    @Test
    void planetTemplateCarriesSerpulosDefaults() {
        JsonObject template = PlanetTemplates.templateFor(ContentType.planet);
        assertEquals(3, template.get("sectorSize").getAsInt(), "serpulo's grid size");
        assertEquals(1, template.get("radius").getAsInt());
        assertEquals("sun", template.get("parent").getAsString());
        assertEquals(170, template.get("startSector").getAsInt(), "serpulo's start sector");
        assertEquals("3c1b8f", template.get("atmosphereColor").getAsString());
        assertEquals(2, template.get("sectorSeed").getAsInt());
        assertTrue(template.get("allowWaves").getAsBoolean());
        assertTrue(template.get("alwaysUnlocked").getAsBoolean());
        assertEquals(0.5, template.get("launchCapacityMultiplier").getAsDouble(), 1e-6);
        assertEquals(7200, template.get("enemyFactoryActivationDelay").getAsInt());
    }

    @Test
    void meshTypesAreTheOnesTheGameParses() {
        // The game's parseMesh switch accepts NoiseMesh/SunMesh/HexSkyMesh/MultiMesh/MatMesh and
        // THROWS on anything else - serpulo's own HexMesh cannot be expressed in JSON, so the
        // template must not use it.
        JsonObject template = PlanetTemplates.templateFor(ContentType.planet);
        assertEquals("NoiseMesh", template.getAsJsonObject("mesh").get("type").getAsString());
        var clouds = template.getAsJsonObject("cloudMesh").getAsJsonArray("meshes");
        assertEquals(2, clouds.size(), "serpulo has two cloud layers");
        for (var cloud : clouds) {
            assertEquals("HexSkyMesh", cloud.getAsJsonObject().get("type").getAsString());
        }
    }

    @Test
    void everyApplicationGetsAFreshMutableCopy() {
        JsonObject first = PlanetTemplates.templateFor(ContentType.planet);
        first.getAsJsonObject("mesh").addProperty("color1", "mutated");
        JsonObject second = PlanetTemplates.templateFor(ContentType.planet);
        assertEquals("3a6b8c", second.getAsJsonObject("mesh").get("color1").getAsString(),
            "template copies must be independent");
        assertEquals(3, PlanetTemplates.templateFor(ContentType.planet)
            .get("sectorSize").getAsInt());
    }

    @Test
    void templateIsRoundTripStableAndSerializable() {
        JsonObject template = PlanetTemplates.templateFor(ContentType.planet);
        // The template is written into content JSON, so it must survive our own writer/parser.
        var reparsed = Json.parseObject(Json.write(template));
        assertEquals(template, reparsed);
    }

    @Test
    void otherTypesHaveNoTemplate() {
        assertNull(PlanetTemplates.templateFor(ContentType.item));
        assertNull(PlanetTemplates.templateFor(ContentType.block));
        assertNull(PlanetTemplates.name(ContentType.block));
        assertEquals("serpulo", PlanetTemplates.name(ContentType.planet));
    }
}
