package art.arcane.gloss.text;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextPipelineMenuTextTest {
    @Test
    void menuTextSubstitutesEmojiTriggers() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.setEmojiFilter(raw -> raw.replace(":smile:", "☺"));

        assertEquals("Shop ☺", pipeline.renderMenuText(null, "Shop :smile:"));
    }

    @Test
    void viewerAwareRenderUsesViewerEmojiPermissions() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.setEmojiFilter(raw -> raw.replace(":smile:", "generic"));
        pipeline.setViewerEmojiFilter((viewer, raw) -> raw.replace(":smile:", viewer.getName()));

        assertEquals("Shop Ada", pipeline.renderMenuText(player("Ada"), "Shop :smile:"));
        assertEquals("Shop generic", pipeline.renderMenuText(null, "Shop :smile:"));
    }

    @Test
    void menuTextUsesTheFullViewerAwarePipeline() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("greet", player -> "World");

        assertEquals("Hello World!", pipeline.renderMenuText(null, "Hello |greet|!"));
        assertEquals("Hello Ada", pipeline.renderMenuText(player("Ada"), "Hello {{ player.name }}"));
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

    private static Player player(String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "hashCode" -> name.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> name;
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}
