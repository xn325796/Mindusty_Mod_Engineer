package dev.modmaker.core.fmt;

import com.google.gson.JsonElement;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Solar-system layout for the orbit preview. Vanilla orbital radii are not stored anywhere - they
 * fall out of Planet's constructor chain: orbitRadius = parent.totalRadius + parent.orbitSpacing +
 * totalRadius (sun: radius 4, orbitSpacing 2). The preview computes the same formula over the bodies
 * it knows about (vanilla planets + the project's planets); siblings outside that set would shift
 * radii slightly in game, so the view is labeled a schematic.
 */
public final class PlanetOrbits {

    private PlanetOrbits() {
    }

    /** Schematic body for the orbit view. */
    public record Body(String name, float radius, float orbitRadius, String color, boolean selected,
        boolean vanilla, boolean star) {
    }

    /** Vanilla planet radii (Planets.java); the sun is the system root. */
    public static final float SUN_RADIUS = 4f;
    public static final float SUN_ORBIT_SPACING = 2f;
    public static final String SUN_COLOR = "ffc64c";
    public static final String[] SUN_COLORS = {"ff7a38", "ff9638", "ffc64c", "ffe371"};
    public static final List<String> VANILLA_CHILDREN = List.of("erekir", "tantros", "serpulo");
    public static final Map<String, Integer> VANILLA_SIZES =
        Map.of("serpulo", 3, "erekir", 2, "tantros", 2);

    /**
     * Bodies of the system the target belongs to, root star first. Handles the four cases: the
     * target is a vanilla planet; it orbits a vanilla planet; it orbits a mod planet; it is itself
     * a system root (no parent). Moons (mod planets whose parent is the target) are included.
     *
     * @param planet the target planet record, or null when the target is the vanilla planet
     *               named {@code vanillaName}
     */
    public static List<Body> systemOf(ModProject project, ContentRecord planet, String vanillaName) {
        List<Body> bodies = new ArrayList<>();
        String bare = planet != null ? planet.name : vanillaName;
        // vanilla planets all have radius 1; VANILLA_SIZES holds grid sizes, not radii
        float targetRadius = planet != null ? radiusOf(planet) : 1f;

        // Walk up the mod-planet parent chain: chain[0] is the outermost known mod planet.
        List<ContentRecord> chain = new ArrayList<>();
        String baseParent = planet != null ? bareParent(project, planet) : "sun";
        if (planet != null) {
            chain.add(planet);
            ContentRecord current = planet;
            int guard = 0;
            while (guard++ < 8) {
                String parentBare = bareParent(project, current);
                if (parentBare == null) {
                    baseParent = null; // the chain's base is itself a system root
                    break;
                }
                ContentRecord parent = planetByName(project, parentBare);
                if (parent == null) {
                    baseParent = parentBare; // vanilla parent (e.g. sun)
                    break;
                }
                chain.add(0, parent);
                current = parent;
            }
        }

        boolean sunContext = "sun".equals(baseParent) || (planet == null && "sun".equals(vanillaName));
        float starRadius;
        if (sunContext) {
            starRadius = SUN_RADIUS;
        } else if (planet != null && chain.size() == 1 && baseParent == null) {
            starRadius = targetRadius; // the target is its own star
        } else {
            starRadius = 1f;
        }

        float starTotal = starRadius;
        bodies.add(new Body(sunContext ? "sun" : (chain.isEmpty() ? bare : chain.get(0).name),
            starRadius, 0, SUN_COLOR, false, sunContext, true));

        if (sunContext) {
            // vanilla siblings for context (serpulo/erekir/tantros share the sun)
            for (String name : VANILLA_CHILDREN) {
                starTotal += 1f;
                boolean isSelected = bare.equals(name);
                bodies.add(new Body(name, 1f, starTotal, null, isSelected, true, false));
            }
            if (planet != null) {
                // the target is a mod planet orbiting the sun; its ring is next
                float orbit = starTotal + SUN_ORBIT_SPACING + targetRadius;
                bodies.add(new Body(bare, targetRadius, orbit, iconColor(planet), true, false, false));
                addMoons(bodies, project, planet, orbit, targetRadius);
            } else if (VANILLA_CHILDREN.contains(bare)) {
                // target IS one of the vanilla siblings: highlight it in place
                markSelected(bodies, bare);
                addVanillaMoons(bodies, project, bare, starTotal);
            }
        } else if (planet != null && chain.size() == 1 && baseParent == null) {
            // standalone root: the target is its own star
            markSelected(bodies, bare);
            addMoons(bodies, project, planet, 0, targetRadius);
        } else if (!chain.isEmpty()) {
            // mod planet chain: each body orbits the previous one
            float previousRadius = starRadius;
            float previousSpacing = 2f;
            for (ContentRecord record : chain) {
                float radius = radiusOf(record);
                float orbit = previousRadius + previousSpacing + radius;
                boolean selected = record == planet;
                bodies.add(new Body(record.name, radius, orbit, iconColor(record), selected, false, false));
                if (selected) {
                    addMoons(bodies, project, record, orbit, radius);
                }
                previousRadius = radius;
                previousSpacing = 2f;
            }
        }
        return bodies;
    }

    private static void markSelected(List<Body> bodies, String name) {
        for (int i = 0; i < bodies.size(); i++) {
            if (bodies.get(i).name().equals(name)) {
                Body body = bodies.get(i);
                bodies.set(i, new Body(body.name(), body.radius(), body.orbitRadius(),
                    body.color(), true, body.vanilla(), body.star()));
            }
        }
    }

    private static void addVanillaMoons(List<Body> bodies, ModProject project, String planetName,
        float planetOrbit) {
        // mod moons of a vanilla planet: not representable without a record; nothing to add.
    }

    private static void addMoons(List<Body> bodies, ModProject project, ContentRecord planet,
        float planetOrbit, float planetRadius) {
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.planet || record.isInline() || record == planet) {
                continue;
            }
            String parent = bareParent(project, record);
            if (planet.name.equals(parent)) {
                float radius = radiusOf(record);
                bodies.add(new Body(record.name, radius,
                    planetOrbit + planetRadius + 2f + radius, iconColor(record), false, false, false));
            }
        }
    }

    private static String bareParent(ModProject project, ContentRecord planet) {
        JsonElement element = planet.fields.get("parent");
        if (element == null || !element.isJsonPrimitive() || element.getAsString().isBlank()) {
            return null;
        }
        String parent = element.getAsString();
        String internal = project.internalName();
        return parent.startsWith(internal + "-") ? parent.substring(internal.length() + 1) : parent;
    }

    private static ContentRecord planetByName(ModProject project, String bare) {
        for (ContentRecord record : project.contents) {
            if (record.type == ContentType.planet && !record.isInline()
                && (record.name.equals(bare) || record.fullName(project.internalName()).equals(bare))) {
                return record;
            }
        }
        return null;
    }

    private static float radiusOf(ContentRecord planet) {
        JsonElement element = planet.fields.get("radius");
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsFloat();
            } catch (NumberFormatException ignored) {
                return 1f;
            }
        }
        return 1f;
    }

    private static String iconColor(ContentRecord planet) {
        JsonElement element = planet.fields.get("iconColor");
        if (element != null && element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return ContentType.planet.color();
    }
}
