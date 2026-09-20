package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackFormatsTest {
    private final PackFormats formats = PackFormats.load();

    @Test
    void theOverrideWinsOverEveryLookup() {
        assertEquals(12, formats.forServer("26.2-R0.1-SNAPSHOT", 12));
        assertEquals(12, formats.forServer(null, 12));
    }

    @Test
    void knownVersionsResolveFromTheTable() {
        assertEquals(88, formats.forServer("26.2-R0.1-SNAPSHOT", 0));
        assertEquals(84, formats.forServer("26.1.2-R0.1-SNAPSHOT", 0));
        assertEquals(88, formats.forServer("26.2", 0));
        assertEquals(97, formats.forServer("26.3-R0.1-SNAPSHOT", 0));
    }

    @Test
    void anOlderUnknownVersionFallsBackToTheNearestOlderEntry() {
        assertEquals(84, formats.forServer("26.1.9-R0.1-SNAPSHOT", 0));
    }

    @Test
    void anUnknownFutureVersionUsesTheNewestEntry() {
        assertEquals(formats.newest(), formats.forServer("27.4-R0.1-SNAPSHOT", 0));
        assertEquals(formats.newest(), formats.forServer("garbage", 0));
        assertEquals(formats.newest(), formats.forServer(null, 0));
    }

    @Test
    void theTableNamesWhereItsValuesCameFrom() {
        assertTrue(formats.source().contains("resource_pack_version"), formats.source());
    }
}
