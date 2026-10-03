package dev.modmaker.core.fmt;

import org.junit.jupiter.api.Test;

import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Orbit schematics for the planet workspace's solar-system view. */
class PlanetOrbitsTest {

    private static ContentRecord planet(ModProject project, String name, String parent, Integer sectorSize) {
        ContentRecord record = new ContentRecord();
        record.id = "id-" + name;
        record.type = ContentType.planet;
        record.name = name;
        record.ext = "json";
        if (parent != null) {
            record.fields.put("parent", new com.google.gson.JsonPrimitive(parent));
        }
        if (sectorSize != null) {
            record.fields.put("sectorSize", new com.google.gson.JsonPrimitive(sectorSize));
        }
        project.contents.add(record);
        return record;
    }

    @Test
    void modPlanetOrbitingTheSunIncludesVanillaContext() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ContentRecord target = planet(project, "my-world", "sun", 2);

        List<PlanetOrbits.Body> bodies = PlanetOrbits.systemOf(project, target, null);

        // star, three vanilla siblings, the target, no moons
        assertEquals(5, bodies.size(), bodies.toString());
        assertTrue(bodies.get(0).star());
        assertEquals("sun", bodies.get(0).name());
        // vanilla rings: 4 + 1, 4 + 2, 4 + 3 (accumulated child radii)
        assertEquals(5f, bodies.get(1).orbitRadius(), 1e-5);
        assertEquals(6f, bodies.get(2).orbitRadius(), 1e-5);
        assertEquals(7f, bodies.get(3).orbitRadius(), 1e-5);
        // the target orbits outside them
        PlanetOrbits.Body self = bodies.get(4);
        assertTrue(self.selected());
        assertEquals(7f + 2f + 1f, self.orbitRadius(), 1e-5);
    }

    @Test
    void sectorTargetResolvesToItsVanillaPlanet() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ContentRecord sector = new ContentRecord();
        sector.id = "s";
        sector.type = ContentType.sector;
        sector.name = "辐射带";
        sector.ext = "json";
        project.contents.add(sector);

        List<PlanetOrbits.Body> bodies =
            PlanetOrbits.systemOf(project, null, "serpulo");
        assertEquals(4, bodies.size()); // sun + erekir + tantros + serpulo
        assertTrue(bodies.get(0).star());
        PlanetOrbits.Body serpulo = bodies.get(3);
        assertEquals("serpulo", serpulo.name());
        assertTrue(serpulo.selected());
        assertEquals(7f, serpulo.orbitRadius(), 1e-5);
    }

    @Test
    void moonsOrbitTheirModPlanet() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ContentRecord home = planet(project, "homeworld", "sun", 2);
        planet(project, "luna", "homeworld", 1);

        List<PlanetOrbits.Body> bodies = PlanetOrbits.systemOf(project, home, null);
        assertEquals(6, bodies.size()); // star + 3 vanilla + homeworld + luna
        PlanetOrbits.Body luna = bodies.get(bodies.size() - 1);
        assertEquals("luna", luna.name());
        // orbit = homeworld orbit (10) + homeworld radius (1) + spacing (2) + luna radius (1)
        assertEquals(10f + 1f + 2f + 1f, luna.orbitRadius(), 1e-5);
    }

    @Test
    void standaloneRootPlanetIsItsOwnStar() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ContentRecord root = planet(project, "lonely", null, 2);
        planet(project, "tiny-moon", "lonely", 1);

        List<PlanetOrbits.Body> bodies = PlanetOrbits.systemOf(project, root, null);
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(0).star());
        assertEquals("lonely", bodies.get(0).name());
        assertEquals(1f, bodies.get(0).radius(), 1e-5);
        assertEquals(0f, bodies.get(0).orbitRadius(), 1e-5);
        assertEquals("tiny-moon", bodies.get(1).name());
    }
}
