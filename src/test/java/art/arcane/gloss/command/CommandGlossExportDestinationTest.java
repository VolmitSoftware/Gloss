package art.arcane.gloss.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /gloss export} re-serializes every document straight over its destination, outside any
 * transaction and without a history copy, so the one destination it must never accept is the live
 * document tree it is reading from.
 */
class CommandGlossExportDestinationTest {
    private static final Path DATA = Path.of("/srv/plugins/Gloss");

    @Test
    void theDataFolderItselfIsRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> CommandGlossExport.destination(DATA, ".")).getMessage().contains("live documents"));
        assertThrows(IllegalArgumentException.class,
            () -> CommandGlossExport.destination(DATA, DATA.toString()));
        assertThrows(IllegalArgumentException.class,
            () -> CommandGlossExport.destination(DATA, "exports/.."));
    }

    @Test
    void aLiveDocumentCollectionIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandGlossExport.destination(DATA, "menus"));
        assertThrows(IllegalArgumentException.class,
            () -> CommandGlossExport.destination(DATA, "boards"));
    }

    @Test
    void anOrdinaryFolderUnderTheDataFolderIsAccepted() {
        assertEquals(DATA.resolve("exports"), CommandGlossExport.destination(DATA, "exports"));
        assertEquals(DATA.resolve("exports").resolve("today"),
            CommandGlossExport.destination(DATA, "exports/today"));
    }

    @Test
    void aDestinationOutsideTheDataFolderIsRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> CommandGlossExport.destination(DATA, "../elsewhere")).getMessage()
            .contains("data folder"));
    }
}
