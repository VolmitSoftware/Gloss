package art.arcane.gloss.text;

import net.md_5.bungee.api.ChatColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextPipelineMenuTextTest {
    @Test
    void menuTextSubstitutesEmojiTriggers() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.setEmojiFilter(raw -> raw.replace(":smile:", "☺"));

        assertEquals("Shop ☺", pipeline.renderMenuText(null, "Shop :smile:"));
    }

    @Test
    void menuTextNeverResolvesFunctionsSoALiteralPipeSurvives() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("greet", player -> "World");

        assertEquals("Hello |greet|!", pipeline.renderMenuText(null, "Hello |greet|!"));
        assertEquals("a | b", pipeline.renderMenuText(null, "a | b"));
        assertEquals("|", pipeline.renderMenuText(null, "|"));
    }

    @Test
    void menuTextTranslatesAmpersandAndBracketHexColours() {
        TextPipeline pipeline = new TextPipeline(null);

        assertEquals(ChatColor.RED + "Danger", pipeline.renderMenuText(null, "&cDanger"));
        assertEquals(ChatColor.of("#ff8800") + "Warm", pipeline.renderMenuText(null, "[ff8800]Warm"));
    }

    @Test
    void menuTextIsEmptyForAbsentInput() {
        TextPipeline pipeline = new TextPipeline(null);

        assertEquals("", pipeline.renderMenuText(null, null));
        assertEquals("", pipeline.renderMenuText(null, ""));
        assertEquals("", pipeline.applyEmoji(null, null));
    }

    @Test
    void theEmojiStageIsANoOpWithoutARunningPlugin() {
        assertEquals("Barrel", TextPipeline.emojiText("Barrel"));
        assertEquals(":smile:", TextPipeline.emojiText(":smile:"));
        assertEquals("&cDanger", TextPipeline.menuText(null, "&cDanger"));
    }

    @Test
    void applyEmojiWithoutARegisteredFilterReturnsTheInput() {
        TextPipeline pipeline = new TextPipeline(null);

        assertEquals(":smile:", pipeline.applyEmoji(null, ":smile:"));
    }
}
