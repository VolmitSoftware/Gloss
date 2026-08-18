package art.arcane.gloss.drop;

import art.arcane.volmlib.util.format.Form;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DropNameFormatterTest {
    @Test
    void prettyEnumNameLowercasesAndSplitsWords() {
        assertEquals("diamond sword", Form.prettyEnumName("DIAMOND_SWORD"));
        assertEquals("stone", Form.prettyEnumName("STONE"));
        assertEquals("oak log", Form.prettyEnumName("OAK_LOG"));
    }

    @Test
    void formatReplacesCountAndType() {
        assertEquals("&732x diamond sword", DropNameFormatter.format("&7{count}x {type}", 32, "diamond sword"));
    }

    @Test
    void formatReplacesRepeatedTokens() {
        assertEquals("3 stone 3 stone", DropNameFormatter.format("{count} {type} {count} {type}", 3, "stone"));
    }

    @Test
    void formatLeavesUnknownTokensAlone() {
        assertEquals("{other} 1 dirt", DropNameFormatter.format("{other} {count} {type}", 1, "dirt"));
    }
}
