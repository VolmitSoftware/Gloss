package art.arcane.gloss.menu.icon;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.MenuSessionOptions;
import art.arcane.gloss.menu.MenuTransform;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMenuIconRefreshTest {
    @Test
    void omittedCadenceAcceleratesOnlyClockDrivenText() {
        assertEquals(10, TextMenuIcon.refreshInterval(text(null), "{{ player.ping }}", true));
        assertEquals(1, TextMenuIcon.refreshInterval(text(null), "{{ time.ticks }}", true));
        assertEquals(1, TextMenuIcon.refreshInterval(text(null), "|animation.rainbow|", true));
        assertEquals(10, TextMenuIcon.refreshInterval(text(null), "{{ time.ticks }}", false));
    }

    @Test
    void explicitCadenceAlwaysWins() {
        assertEquals(0, TextMenuIcon.refreshInterval(text(0), "{{ time.ticks }}", true));
        assertEquals(7, TextMenuIcon.refreshInterval(text(7), "|animation.rainbow|", true));
    }

    @Test
    void adaptiveTextUpdatesRequireReplacementWhenAnExplicitDefaultCadenceIsChosen() throws MenuIconException {
        TextMenuIcon icon = icon(null);
        assertTrue(icon.matchesAppearance(null, null, null));
        assertFalse(icon.matchesAppearance(null, null, 10));
    }

    @Test
    void explicitDefaultCadenceRequiresReplacementWhenAdaptiveRefreshIsRestored() throws MenuIconException {
        TextMenuIcon icon = icon(10);
        assertTrue(icon.matchesAppearance(null, null, 10));
        assertFalse(icon.matchesAppearance(null, null, null));
    }

    private static TextMenuIcon icon(Integer refreshTicks) throws MenuIconException {
        MenuDefinitionData definition = new MenuDefinitionData(new Vector(), false, false, 8D,
            false, false, List.of(), List.of(), ShowCondition.ALWAYS);
        definition.setId("text-refresh");
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "text refresh viewer";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        Location anchor = new Location(null, 0D, 0D, 0D);
        MenuTransform transform = new MenuTransform(anchor, new Vector(), 0F, 0F, 0F, 1F);
        MenuSession session = new MenuSession(definition, player,
            MenuSessionOptions.positioned(transform, request -> NavigationResult.DENIED, 1F));
        return new TextMenuIcon(session, anchor, text(refreshTicks));
    }

    private static TextIconData text(Integer refreshTicks) {
        return new TextIconData("", null, refreshTicks, null);
    }
}
