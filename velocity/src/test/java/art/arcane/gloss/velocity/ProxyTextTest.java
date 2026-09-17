package art.arcane.gloss.velocity;

import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.animation.AnimationMode;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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

    @Test
    void replacesEmojiTokensAndTriggersInFileIdOrder() {
        ProxyText text = new ProxyText(mock(ProxyServer.class));
        text.content(new ProxyTextDocuments.Content(true, false,
            List.of(emoji("heart", "<3", "\u2764"),
                new ProxyTextDocuments.Emoji("off", ":(", "x", false, ProxyText.parseExpression("true")),
                emoji("star", "", "*"), emoji("starofdavid", "", "#")),
            Map.of()));
        ExpressionScope scope = text.scope(null, null);
        assertEquals("\u2764 \u2764 * #", text.plain(":heart: <3 :star: :starofdavid:", scope));
        assertEquals(":off: :(", text.plain(":off: :(", scope));
        assertEquals("plain text", text.plain("plain text", scope));
    }

    @Test
    void hidesEmojiWhoseShowConditionFailsForTheViewer() {
        ProxyText text = new ProxyText(mock(ProxyServer.class));
        text.content(new ProxyTextDocuments.Content(true, false,
            List.of(new ProxyTextDocuments.Emoji("crown", "^^", "\u2655", true,
                ProxyText.parseExpression("hasPermission('gloss.staff')"))), Map.of()));
        Player staff = mock(Player.class);
        when(staff.hasPermission("gloss.staff")).thenReturn(true);
        Player guest = mock(Player.class);
        assertEquals("\u2655 \u2655", text.plain(":crown: ^^", text.scope(staff, staff)));
        assertEquals(":crown: ^^", text.plain(":crown: ^^", text.scope(guest, guest)));
    }

    @Test
    void rendersNamedAnimationFramesAtThePinnedClock() {
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPlayerCount()).thenReturn(12);
        ProxyText text = new ProxyText(proxy);
        text.content(new ProxyTextDocuments.Content(false, true, List.of(),
            Map.of("hub", animation("hub", "true", List.of("one $online", "two {{ 1 + 1 }}", "three")))));
        assertEquals("[one 12]", text.plain("[|animation.hub|]", text.scope(null, null, 0L)));
        assertEquals("[two 2]", text.plain("[|animation.hub|]", text.scope(null, null, 1000L)));
        assertEquals("[three]", text.plain("[|animation.hub|]", text.scope(null, null, 2999L)));
        assertEquals("[one 12]", text.plain("[|animation.hub|]", text.scope(null, null, 3000L)));
    }

    @Test
    void hiddenAnimationsRenderEmptyAndNestedTokensStayLiteral() {
        ProxyText text = new ProxyText(mock(ProxyServer.class));
        text.content(new ProxyTextDocuments.Content(false, true, List.of(),
            Map.of("hidden", animation("hidden", "viewer.present", List.of("frame")),
                "outer", animation("outer", "true", List.of("deep |animation.hidden|")))));
        assertEquals("[]", text.plain("[|animation.hidden|]", text.scope(null, null, 0L)));
        assertEquals("[deep |animation.hidden|]", text.plain("[|animation.outer|]", text.scope(null, null, 0L)));
    }

    @Test
    void leavesUnknownTokensAndDisabledCatalogsExactlyAsWritten() {
        ProxyText text = new ProxyText(mock(ProxyServer.class));
        text.content(new ProxyTextDocuments.Content(true, true, List.of(emoji("heart", "<3", "\u2764")),
            Map.of("hub", animation("hub", "true", List.of("frame")))));
        ExpressionScope scope = text.scope(null, null, 0L);
        assertEquals("|animation.missing| |metric.hub| frame",
            text.plain("|animation.missing| |metric.hub| |animation.hub|", scope));
        text.content(new ProxyTextDocuments.Content(false, false, List.of(emoji("heart", "<3", "\u2764")),
            Map.of("hub", animation("hub", "true", List.of("frame")))));
        assertEquals(":heart: <3 |animation.hub|", text.plain(":heart: <3 |animation.hub|", text.scope(null, null, 0L)));
    }

    private static ProxyTextDocuments.Emoji emoji(String id, String trigger, String value) {
        return new ProxyTextDocuments.Emoji(id, trigger, value, true, ProxyText.parseExpression("true"));
    }

    private static ProxyTextDocuments.Animation animation(String id, String show, List<String> frames) {
        return new ProxyTextDocuments.Animation(id, ProxyText.parseExpression(show),
            new AnimationClip(id, 1.0D, AnimationMode.ASCEND, frames));
    }
}
