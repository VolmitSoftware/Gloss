package art.arcane.gloss.text;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextExpressionRendererTest {
    private final TextExpressionRenderer renderer = new TextExpressionRenderer(null, () -> 19.8D);

    @Test
    void evaluatesInlineMathAndTextFunctions() {
        assertEquals("TPS 19.8", renderer.render(null, "TPS {{ fixed(19.84, 1) }}"));
        assertEquals("[FF00AA]Live", renderer.render(null, "{{ hex(#FF00AA) }}Live"));
        assertEquals("[###-------]", renderer.render(null, "[{{ bar(3, 10, 10, '#', '-') }}]"));
        assertEquals("B", renderer.render(null, "{{ select(['A', 'B', 'C'], 4) }}"));
    }

    @Test
    void supportsTimeDrivenAuthoredAnimation() {
        String rendered = renderer.render(null, "{{ select(['&c', '&b'], floor(time.seconds * 4)) }}Pulse");
        assertTrue(rendered.equals("&cPulse") || rendered.equals("&bPulse"));
    }

    @Test
    void timeDrivenSelectionPreservesEpochScaleIndices() {
        assertEquals("&a▲ &f&lBOOSTED", renderer.render(null,
            "{{ select(['&a', '&8', '&a', '&7'], floor(1750000000 * 4)) }}▲ &f&lBOOSTED"));
        assertEquals("&8▲ &f&lBOOSTED", renderer.render(null,
            "{{ select(['&a', '&8', '&a', '&7'], floor(1750000000.25 * 4)) }}▲ &f&lBOOSTED"));
    }

    @Test
    void leavesInvalidOrUnclosedExpressionsEditable() {
        assertEquals("A {{ nope() }} B", renderer.render(null, "A {{ nope() }} B"));
        assertEquals("A {{ 1 + 2", renderer.render(null, "A {{ 1 + 2"));
    }

    @Test
    void leavesPapiLiteralWithoutAPlayer() {
        assertEquals("Hello %player_name%", renderer.render(null, "Hello {{ papi('player_name') }}"));
    }

    @Test
    void exposesInternalServerTpsWithoutAnIntegrationPlugin() {
        assertEquals("TPS 19.8", renderer.render(null, "TPS {{ fixed(server.tps, 1) }}"));
    }

    @Test
    void optionalSourcesAcceptExplicitFallbacks() {
        assertEquals("Member", renderer.render(null, "{{ papi('vault_prefix', 'Member') }}"));
        assertEquals("0", renderer.render(null, "{{ papiNumber('vault_eco_balance', 0) }}"));
        assertEquals("19.8", renderer.render(null, "{{ fixed(metric('react.tps', server.tps), 1) }}"));
    }

    @Test
    void rendersLegacyShowcaseLinesWithoutPlaceholderApiOrReact() {
        TextExpressionRenderer showcase = new TextExpressionRenderer(null,
            new TextExpressionRenderer.RuntimeValues(() -> 12, () -> 100, () -> 19.8D));
        Player viewer = player("Ada", 42, 15.0D, 7);
        List<String> authored = List.of(
            "&7Player &f{{ papi('player_name') }}",
            "&7Ping {{ papiNumber('player_ping') < 80 ? '&a' : papiNumber('player_ping') < 160 ? '&e' : '&c' }}{{ papi('player_ping') }}ms",
            "&7Health &a{{ bar(papiNumber('player_health'), 20, 8, '■', '□') }}",
            "&7Online &a{{ papi('server_online') }}&8/&a{{ papi('server_max_players') }}",
            "&7TPS &a{{ fixed(metric('react.tps'), 1) }}"
        );
        List<String> expected = List.of(
            "&7Player &fAda",
            "&7Ping &a42ms",
            "&7Health &a■■■■■■□□",
            "&7Online &a12&8/&a100",
            "&7TPS &a19.8"
        );

        for (int index = 0; index < authored.size(); index++) {
            String rendered = showcase.render(viewer, authored.get(index));
            assertEquals(expected.get(index), rendered);
            assertFalse(rendered.contains("{{"));
            assertFalse(rendered.contains("%"));
            assertFalse(rendered.contains("GlossONLINE"));
        }
    }

    private Player player(String name, int ping, double health, int level) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getPing" -> ping;
                case "getHealth" -> health;
                case "getLevel" -> level;
                case "toString" -> name;
                case "hashCode" -> name.hashCode();
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
    }

    private Object defaultValue(Class<?> type) {
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
