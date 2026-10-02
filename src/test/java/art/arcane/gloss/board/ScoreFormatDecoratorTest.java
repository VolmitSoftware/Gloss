package art.arcane.gloss.board;

import art.arcane.gloss.util.common.StubPacketEventsApi;
import com.github.retrooper.packetevents.protocol.score.BlankScoreFormat;
import com.github.retrooper.packetevents.protocol.score.FixedScoreFormat;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.protocol.score.StyledScoreFormat;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The sidebar's own score packets carry the number format; the decorator writes the authored value
 * onto the packets that already leave rather than sending anything of its own.
 */
class ScoreFormatDecoratorTest {
    private static final UUID VIEWER = UUID.randomUUID();
    private static final String OBJECTIVE = "board";

    @BeforeAll
    static void installPacketEventsApi() {
        StubPacketEventsApi.install();
    }

    @AfterAll
    static void clearPacketEventsApi() {
        StubPacketEventsApi.clear();
    }

    @Test
    void aFixedLineGetsItsRenderedValueAsTheScoreFormat() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", ScoreFormat.fixedScore(Component.text("12")))));
        WrapperPlayServerUpdateScore packet = packet("entry-0", OBJECTIVE);

        assertEquals(true, decorator.decorate(VIEWER, packet));

        FixedScoreFormat format = assertInstanceOf(FixedScoreFormat.class, packet.getScoreFormat());
        assertEquals(Component.text("12"), format.getValue());
    }

    @Test
    void aBlankLineGetsTheBlankFormat() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-3", ScoreFormat.blankScore())));
        WrapperPlayServerUpdateScore packet = packet("entry-3", OBJECTIVE);

        assertEquals(true, decorator.decorate(VIEWER, packet));
        assertInstanceOf(BlankScoreFormat.class, packet.getScoreFormat());
    }

    @Test
    void aForeignObjectiveIsLeftUntouched() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", ScoreFormat.blankScore())));
        WrapperPlayServerUpdateScore packet = packet("entry-0", "someone-else");

        assertEquals(false, decorator.decorate(VIEWER, packet));
        assertNull(packet.getScoreFormat());
    }

    @Test
    void aLineWithNoAuthoredFormatIsLeftUntouched() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", ScoreFormat.blankScore())));
        WrapperPlayServerUpdateScore packet = packet("entry-9", OBJECTIVE);

        assertEquals(false, decorator.decorate(VIEWER, packet));
        assertNull(packet.getScoreFormat());
    }

    @Test
    void aViewerWithNoPublishedIndexIsLeftUntouched() {
        ScoreFormatDecorator decorator = decorator(null);
        WrapperPlayServerUpdateScore packet = packet("entry-0", OBJECTIVE);

        assertEquals(false, decorator.decorate(VIEWER, packet));
        assertNull(packet.getScoreFormat());
    }

    @Test
    void anEmptyIndexIsNeverPublished() {
        assertNull(BoardFormatIndex.of(OBJECTIVE, Map.of()));
        assertNull(BoardFormatIndex.of(null, Map.of("entry-0", ScoreFormat.blankScore())));
    }

    @Test
    void numberFormatOverridesTheHiddenObjectiveNumbers() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", BoardLineFormat.NUMBER.scoreFormat(null))));
        WrapperPlayServerUpdateScore packet = packet("entry-0", OBJECTIVE);
        packet.setScoreFormat(ScoreFormat.blankScore());

        decorator.decorate(VIEWER, packet);

        StyledScoreFormat format = assertInstanceOf(StyledScoreFormat.class, packet.getScoreFormat());
        assertEquals(Style.empty(), format.getStyle());
        assertEquals(Component.text("1"), format.format(1));
    }

    @Test
    void styledFormatUsesTheAuthoredColorForTheNumericScore() {
        StyledScoreFormat format = assertInstanceOf(StyledScoreFormat.class,
            BoardLineFormat.STYLED.scoreFormat("&c"));

        assertEquals(NamedTextColor.RED, format.getStyle().color());
        assertEquals(Component.text("7").color(NamedTextColor.RED), format.format(7));
    }

    private static ScoreFormatDecorator decorator(BoardFormatIndex index) {
        return new ScoreFormatDecorator(viewer -> index);
    }

    private static WrapperPlayServerUpdateScore packet(String entry, String objective) {
        return new WrapperPlayServerUpdateScore(entry, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
            objective, Optional.of(1));
    }

}
