package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphLedgerTest {
    @TempDir
    Path folder;

    @Test
    void allocatesFromTheConfiguredBaseUpward() {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE);

        assertEquals(0xE000, ledger.codepointFor("logo"));
        assertEquals(0xE001, ledger.codepointFor("coin"));
        assertEquals(0xE000, ledger.codepointFor("logo"));
    }

    @Test
    void honorsANonDefaultBase() {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), 0xF000);

        assertEquals(0xF000, ledger.codepointFor("logo"));
        assertEquals(0xF000, ledger.base());
    }

    @Test
    void survivesAReloadWithoutRenumbering() {
        Path file = folder.resolve("ledger.json");
        GlyphLedger first = GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE);
        int logo = first.codepointFor("logo");
        int coin = first.codepointFor("coin");
        int space = first.spaceCodepoint(-4);

        GlyphLedger second = GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE);

        assertEquals(logo, second.codepointFor("logo"));
        assertEquals(coin, second.codepointFor("coin"));
        assertEquals(space, second.spaceCodepoint(-4));
        assertNotEquals(logo, second.codepointFor("badge"));
        assertNotEquals(coin, second.codepointFor("badge"));
    }

    @Test
    void keepsACodepointWhenAGlyphIsRemovedAndAddedBack() {
        Path file = folder.resolve("ledger.json");
        GlyphLedger ledger = GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE);
        int coin = ledger.codepointFor("coin");
        ledger.codepointFor("logo");

        GlyphLedger afterRemoval = GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE);

        assertEquals(coin, afterRemoval.codepointFor("coin"));
    }

    @Test
    void spaceCodepointsAreDistinctPerWidthAndNeverCollideWithGlyphs() {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE);

        int glyph = ledger.codepointFor("coin");
        int minusOne = ledger.spaceCodepoint(-1);
        int plusOne = ledger.spaceCodepoint(1);

        assertNotEquals(glyph, minusOne);
        assertNotEquals(minusOne, plusOne);
        assertEquals(minusOne, ledger.spaceCodepoint(-1));
    }

    @Test
    void writesTheLedgerFileAfterEachAllocation() throws IOException {
        Path file = folder.resolve("ledger.json");
        GlyphLedger ledger = GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE);
        ledger.codepointFor("coin");

        String raw = Files.readString(file);
        assertTrue(raw.contains("\"coin\""), raw);
        assertTrue(raw.contains("\"base\""), raw);
    }

    @Test
    void readsALedgerWrittenByHand() throws IOException {
        Path file = folder.resolve("ledger.json");
        Files.writeString(file, """
            {"base":57344,"glyphs":{"logo":57400},"spaces":{"-4":57500}}
            """);

        GlyphLedger ledger = GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE);

        assertEquals(57400, ledger.codepointFor("logo"));
        assertEquals(57500, ledger.spaceCodepoint(-4));
        assertEquals(0xE000, ledger.codepointFor("coin"));
    }

    @Test
    void refusesToAllocatePastThePrivateUseArea() {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.MAX_CODEPOINT);

        assertEquals(GlyphLedger.MAX_CODEPOINT, ledger.codepointFor("last"));
        assertThrows(IllegalStateException.class, () -> ledger.codepointFor("overflow"));
    }

    @Test
    void anUnreadableLedgerRefusesRatherThanRenumberingEveryGlyph() throws IOException {
        Path file = folder.resolve("ledger.json");
        String handEdited = "{\"base\": 57344, \"glyphs\": {\"coin\": 57344,}}";
        Files.writeString(file, handEdited);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE));

        assertTrue(refused.getMessage().contains("ledger.json"), refused.getMessage());
        assertEquals(handEdited, Files.readString(file),
            "the operator's file is the only record of the assignment and must survive untouched");
    }

    @Test
    void aRefusedLedgerIsReadableAgainOnceTheOperatorFixesIt() throws IOException {
        Path file = folder.resolve("ledger.json");
        Files.writeString(file, "{\"base\": 57344, \"glyphs\": {\"coin\": 57350,}}");
        assertThrows(IllegalStateException.class, () -> GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE));

        Files.writeString(file, "{\"base\": 57344, \"glyphs\": {\"coin\": 57350}}");

        assertEquals(57350, GlyphLedger.load(file, GlyphLedger.DEFAULT_BASE).codepointFor("coin"));
    }
}
