package art.arcane.gloss.names;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NamesCatalogTest {
    @Test
    void sharedOverridesNormalizeVanillaIdsButKeepCustomNamespacesAndWorldCase() {
        NamesCatalog catalog = new NamesCatalog(NamesDoc.parse("names.json", """
            {"schemaVersion":1,"revision":1,"materials":{"minecraft:oak_log":"Timber","custom:oak_log":"Custom timber"},
             "worlds":{"MainWorld":"Main world"},"groups":{"ADMIN":"Staff"},"dimensions":{"normal":"Home"}}
            """));
        assertEquals("Timber", catalog.resolve(NameCategory.MATERIALS, "OAK_LOG"));
        assertEquals("Timber", catalog.resolve(NameCategory.MATERIALS, "minecraft:oak_log"));
        assertEquals("Custom timber", catalog.resolve(NameCategory.MATERIALS, "custom:oak_log"));
        assertEquals("Main world", catalog.resolve(NameCategory.WORLDS, "MainWorld"));
        assertEquals("Mainworld", catalog.resolve(NameCategory.WORLDS, "mainworld"));
        assertEquals("Staff", catalog.resolve(NameCategory.GROUPS, "admin"));
        assertEquals("Home", catalog.resolve(NameCategory.DIMENSIONS, "minecraft:overworld"));
    }

    @Test
    void fallbackNamesAreReadableAndMissingGroupsStayEmpty() {
        NamesCatalog catalog = new NamesCatalog(NamesDoc.DEFAULTS);
        assertEquals("Oak Log", catalog.resolve(NameCategory.MATERIALS, "minecraft:oak_log"));
        assertEquals("Jack o'Lantern", catalog.resolve(NameCategory.MATERIALS, "JACK_O_LANTERN"));
        assertEquals("Heart of the Sea", catalog.resolve(NameCategory.MATERIALS, "HEART_OF_THE_SEA"));
        assertEquals("Zombie Villager", catalog.resolve(NameCategory.ENTITIES, "zombie_villager"));
        assertEquals("World Nether", catalog.resolve(NameCategory.WORLDS, "world_nether"));
        assertEquals("Creative", catalog.resolve(NameCategory.GAME_MODES, "CREATIVE"));
        assertEquals("Fire Tick", catalog.resolve(NameCategory.DAMAGE_CAUSES, "FIRE_TICK"));
        assertEquals("Slow Falling", catalog.resolve(NameCategory.EFFECTS, "minecraft:slow_falling"));
        assertEquals("The Nether", catalog.resolve(NameCategory.DIMENSIONS, "NETHER"));
        assertEquals("", catalog.resolve(NameCategory.GROUPS, null));
        assertEquals("", catalog.resolve(NameCategory.GROUPS, ""));
    }

    @Test
    void ambiguousAndInvalidOverridesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> NamesDoc.parse("names.json", """
            {"schemaVersion":1,"revision":1,"materials":{"OAK_LOG":"A","minecraft:oak_log":"B"}}
            """));
        assertThrows(IllegalArgumentException.class, () -> NamesDoc.parse("names.json", """
            {"schemaVersion":1,"revision":1,"materials":{"oak_log":42}}
            """));
        assertThrows(IllegalArgumentException.class, () -> NamesDoc.parse("names.json", """
            {"schemaVersion":1,"revision":1,"worlds":{" ":"A"}}
            """));
    }
}
