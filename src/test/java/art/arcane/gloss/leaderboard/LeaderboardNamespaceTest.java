package art.arcane.gloss.leaderboard;

import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LeaderboardNamespaceTest {
    private static final UUID ALPHA = UUID.nameUUIDFromBytes("alpha".getBytes());
    private static final UUID BRAVO = UUID.nameUUIDFromBytes("bravo".getBytes());

    private static final LeaderboardView VIEW = new LeaderboardView("kills", 2, 1_700_000_000_000L,
        List.of(new LeaderboardView.Row(BRAVO, "Bravo", 30.0D, "30 kills"),
            new LeaderboardView.Row(ALPHA, "Alpha", 10.0D, "10 kills")));

    private static final LeaderboardNamespace NAMESPACE =
        new LeaderboardNamespace(Map.of("kills", VIEW)::get);

    @Test
    void thePrefixIsTheDocumentedOne() {
        assertEquals("leaderboard", NAMESPACE.prefix());
    }

    @Test
    void aRankedRowExposesEveryField() {
        assertEquals("Bravo", resolve("kills.1.name"));
        assertEquals(30.0D, resolve("kills.1.value"));
        assertEquals("30 kills", resolve("kills.1.formatted"));
        assertEquals(BRAVO.toString(), resolve("kills.1.uuid"));
        assertEquals(1.0D, resolve("kills.1.rank"));
    }

    @Test
    void boardWideFieldsResolveWithoutARank() {
        assertEquals(2.0D, resolve("kills.size"));
        assertEquals(1_700_000_000.0D, resolve("kills.resetAt"));
    }

    @Test
    void theViewerOwnRowResolvesUnderMe() {
        assertEquals(2.0D, resolve("kills.me.rank", ALPHA));
        assertEquals(10.0D, resolve("kills.me.value", ALPHA));
        assertEquals("10 kills", resolve("kills.me.formatted", ALPHA));
    }

    @Test
    void anUnrankedViewerReadsZeroAndAnEmptyLabel() {
        UUID stranger = UUID.randomUUID();

        assertEquals(0.0D, resolve("kills.me.rank", stranger));
        assertEquals(0.0D, resolve("kills.me.value", stranger));
        assertEquals("", resolve("kills.me.formatted", stranger));
    }

    @Test
    void anUnknownBoardRankOrFieldResolvesToNull() {
        assertNull(resolve("missing.1.name"));
        assertNull(resolve("kills.9.name"));
        assertNull(resolve("kills.1.nope"));
        assertNull(resolve("kills"));
        assertNull(resolve("kills.me.nope", ALPHA));
    }

    @Test
    void theViewerlessScopeHasNoMeRow() {
        assertNull(resolve("kills.me.rank"));
    }

    private static Object resolve(String suffix) {
        return NAMESPACE.resolve(suffix, ExprVariableContext.empty());
    }

    private static Object resolve(String suffix, UUID viewerId) {
        return NAMESPACE.resolve(suffix, new ExprVariableContext(player(viewerId), null, null, null));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(LeaderboardNamespaceTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
