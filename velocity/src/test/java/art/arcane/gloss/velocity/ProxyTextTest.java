package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ProxyTextTest {
    @Test
    void expandsExpressionsWithSubjectAndViewerPermissionContexts() {
        ProxyServer proxy = mock(ProxyServer.class);
        Player viewer = mock(Player.class);
        Player subject = mock(Player.class);
        when(proxy.getPlayerCount()).thenReturn(12);
        when(viewer.getUsername()).thenReturn("Viewer");
        when(subject.getUsername()).thenReturn("Subject");
        when(subject.getCurrentServer()).thenReturn(Optional.empty());
        when(subject.getPing()).thenReturn(42L);
        when(viewer.hasPermission("gloss.staff")).thenReturn(true);
        ProxyText text = new ProxyText(proxy);
        ExpressionScope scope = text.scope(viewer, subject);
        assertEquals("Subject: 42 / 12 / 24", text.plain("$player: $ping / $online / {{ server.online * 2 }}", scope));
        assertTrue(text.test(ExprParser.parse("hasPermission('viewer', 'gloss.staff') && subject.name == 'Subject'"), scope));
        assertFalse(text.test(ExprParser.parse("hasPermission('subject', 'gloss.staff')"), scope));
        assertEquals("Viewer", text.plain("{{ viewer.name }}", scope));
    }

    @Test
    void expressionsUseSharedMathAndAnimationFunctions() {
        ProxyText text = new ProxyText(mock(ProxyServer.class));
        ExpressionScope scope = text.scope(null, null);
        assertEquals("12.35", text.plain("{{ fixed(12.345, 2) }}", scope));
        assertEquals("first", text.plain("{{ select(['first', 'second'], 0) }}", scope));
        assertTrue(text.test(ExprParser.parse("!viewer.present && oneOf('hub', ['hub', 'games'])"), scope));
    }
}
