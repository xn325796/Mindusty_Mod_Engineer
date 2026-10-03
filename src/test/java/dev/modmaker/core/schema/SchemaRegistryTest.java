package dev.modmaker.core.schema;

import dev.modmaker.core.fmt.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SchemaRegistryTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void load() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")),
            "run `./gradlew schemaBootstrap` first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    @Test
    void hasBothCuratedAndDraftedClasses() {
        assertTrue(registry.isCurated("Item"));
        assertTrue(registry.isCurated("GenericCrafter"));
        assertNotNull(registry.schema("Block"));
        assertFalse(registry.classNames().size() < 100,
            "the source scan should draft well over a hundred classes, saw " + registry.classNames().size());
    }

    @Test
    void curatedFieldsWinAndCarryTheirWidgetType() {
        FieldDef color = field(registry.fieldsOf("Item"), "color");
        assertTrue(color.curated());
        assertEquals(FieldType.color, color.type());

        FieldDef requirements = field(registry.fieldsOf("Block"), "requirements");
        assertEquals(FieldType.stackArray, requirements.type());
        assertEquals("item", requirements.stackKind());
        assertTrue(requirements.isReference());

        FieldDef visibility = field(registry.fieldsOf("Block"), "buildVisibility");
        assertEquals(FieldType.enumeration, visibility.type());
        assertTrue(visibility.enumValues().contains("sandboxOnly"));
    }

    @Test
    void inheritedFieldsComeFromTheWholeChain() {
        List<FieldDef> crafter = registry.fieldsOf("GenericCrafter");
        assertNotNull(field(crafter, "craftTime"), "own field");
        assertNotNull(field(crafter, "health"), "inherited from Block");
        assertNotNull(field(crafter, "requirements"), "inherited from Block");
        // Base fields are ordered before the class's own fields.
        assertTrue(indexOf(crafter, "health") < indexOf(crafter, "craftTime"));
    }

    @Test
    void classesForATypeStartWithTheBaseClass() {
        List<String> blocks = registry.classesForType(ContentType.block);
        assertEquals("Block", blocks.get(0));
        assertTrue(blocks.contains("GenericCrafter"));
        assertTrue(blocks.contains("ItemTurret"));
        assertTrue(blocks.contains("Wall"));
        assertEquals("Item", registry.baseClassOf(ContentType.item));
        // baseClassOf reports the hierarchy root; that weather needs an explicit type to be valid
        // is expressed by resolveClass returning null (see below).
        assertEquals("Weather", registry.baseClassOf(ContentType.weather));
    }

    @Test
    void resolvesTheClassForARecord() {
        assertEquals("Block", registry.resolveClass(ContentType.block, null));
        assertEquals("GenericCrafter", registry.resolveClass(ContentType.block, "GenericCrafter"));
        assertEquals("BulletType", registry.resolveClass(ContentType.bullet, null));
        assertNull(registry.resolveClass(ContentType.weather, null));
    }

    @Test
    void knowsVanillaContentNames() {
        Set<String> items = registry.vanillaNames(ContentType.item);
        assertTrue(items.contains("copper"), "vanilla items should include copper, saw " + items.size());
        assertTrue(items.contains("lead"));
        assertTrue(registry.isVanillaName(ContentType.block, "copper-wall"));
        assertTrue(registry.vanillaNames(ContentType.block).size() > 100,
            "expected a large vanilla block list, saw " + registry.vanillaNames(ContentType.block).size());
        assertFalse(registry.isVanillaName(ContentType.block, "my-mod-not-real"));
    }

    @Test
    void adaptiveClassSwitchingStaysInTheSameBranch() {
        // A battery may become a power node or go back to the generic base, but never an item bridge.
        List<String> fromBattery = registry.relatedClasses(ContentType.block, "Battery");
        assertTrue(fromBattery.contains("Battery"));
        assertTrue(fromBattery.contains("Block"), "the base class must stay selectable");
        assertTrue(fromBattery.contains("PowerNode"), fromBattery.toString());
        assertFalse(fromBattery.contains("ItemBridge"), fromBattery.toString());
        assertFalse(fromBattery.contains("GenericCrafter"), fromBattery.toString());
        assertFalse(fromBattery.contains("Wall"), fromBattery.toString());

        // Turrets interoperate with turrets, not with drills.
        List<String> fromItemTurret = registry.relatedClasses(ContentType.block, "ItemTurret");
        assertTrue(fromItemTurret.contains("PowerTurret"), fromItemTurret.toString());
        assertTrue(fromItemTurret.contains("LiquidTurret"), fromItemTurret.toString());
        assertTrue(fromItemTurret.contains("Block"));
        assertFalse(fromItemTurret.contains("Drill"), fromItemTurret.toString());

        // From the base class there is nothing to narrow by: the full list is offered.
        assertEquals(registry.classesForType(ContentType.block),
            registry.relatedClasses(ContentType.block, "Block"));
    }

    private static FieldDef field(List<FieldDef> fields, String name) {
        for (FieldDef def : fields) {
            if (def.name().equals(name)) {
                return def;
            }
        }
        return null;
    }

    private static int indexOf(List<FieldDef> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
