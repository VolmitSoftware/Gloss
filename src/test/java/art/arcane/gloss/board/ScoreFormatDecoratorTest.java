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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The sidebar's own score packets carry the number format; the decorator writes the authored value
 * onto initial packets and sends score updates when only the authored format changes.
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
            Map.of("entry-0", ScoreFormat.fixedScore(Component.text("12"))), false));
        WrapperPlayServerUpdateScore packet = packet("entry-0", OBJECTIVE);

        assertEquals(true, decorator.decorate(VIEWER, packet));

        FixedScoreFormat format = assertInstanceOf(FixedScoreFormat.class, packet.getScoreFormat());
        assertEquals(Component.text("12"), format.getValue());
    }

    @Test
    void aBlankLineGetsTheBlankFormat() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-3", ScoreFormat.blankScore()), false));
        WrapperPlayServerUpdateScore packet = packet("entry-3", OBJECTIVE);

        assertEquals(true, decorator.decorate(VIEWER, packet));
        assertInstanceOf(BlankScoreFormat.class, packet.getScoreFormat());
    }

    @Test
    void aForeignObjectiveIsLeftUntouched() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", ScoreFormat.blankScore()), false));
        WrapperPlayServerUpdateScore packet = packet("entry-0", "someone-else");

        assertEquals(false, decorator.decorate(VIEWER, packet));
        assertNull(packet.getScoreFormat());
    }

    @Test
    void aLineWithNoAuthoredFormatIsLeftUntouched() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", ScoreFormat.blankScore()), false));
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
    void anEmptyIndexRetainsOwnershipAndTheDefaultFormat() {
        BoardFormatIndex index = BoardFormatIndex.of(OBJECTIVE, Map.of(), true);
        assertInstanceOf(BlankScoreFormat.class, index.format(OBJECTIVE, "entry-0"));
        assertNull(BoardFormatIndex.of(null, Map.of("entry-0", ScoreFormat.blankScore()), false));
    }

    @Test
    void numberFormatOverridesTheHiddenObjectiveNumbers() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", BoardLineFormat.NUMBER.scoreFormat(null)), false));
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

    @Test
    void aValueOnlyChangeSendsOneScoreWithoutChangingItsRankOrDisplayName() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        WrapperPlayServerUpdateScore initial = packet("entry-0", OBJECTIVE);
        initial.setValue(Optional.of(15));
        initial.setEntityDisplayName(Component.text("Balance"));
        decorator.decorate(VIEWER, initial);
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        decorator.publish(VIEWER, fixed("13"), updates::add);

        assertEquals(1, updates.size());
        WrapperPlayServerUpdateScore update = updates.getFirst();
        assertEquals(OBJECTIVE, update.getObjectiveName());
        assertEquals("entry-0", update.getEntityName());
        assertEquals(Optional.of(15), update.getValue());
        assertEquals(Component.text("Balance"), update.getEntityDisplayName());
        assertEquals(Component.text("13"), assertInstanceOf(FixedScoreFormat.class, update.getScoreFormat()).getValue());
    }

    @Test
    void separatelyRenderedEquivalentFormatsDoNotSendUpdates() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        decorator.decorate(VIEWER, packet("entry-0", OBJECTIVE));
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        for (int pass = 0; pass < 10; pass++) {
            decorator.publish(VIEWER, fixed("12"), updates::add);
        }

        assertEquals(List.of(), updates);
    }

    @Test
    void removingAFormatRestoresTheCurrentNumberVisibility() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        decorator.decorate(VIEWER, packet("entry-0", OBJECTIVE));
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        decorator.publish(VIEWER, BoardFormatIndex.of(OBJECTIVE, Map.of(), true), updates::add);
        decorator.publish(VIEWER, BoardFormatIndex.of(OBJECTIVE, Map.of(), false), updates::add);

        assertEquals(2, updates.size());
        assertInstanceOf(BlankScoreFormat.class, updates.get(0).getScoreFormat());
        assertNull(updates.get(1).getScoreFormat());
    }

    @Test
    void addingAFormatToAnExistingPlainRowUpdatesIt() {
        ScoreFormatDecorator decorator = decorator(BoardFormatIndex.of(OBJECTIVE, Map.of(), false));
        decorator.decorate(VIEWER, packet("entry-0", OBJECTIVE));
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        decorator.publish(VIEWER, fixed("12"), updates::add);

        assertEquals(1, updates.size());
        assertEquals(Component.text("12"), assertInstanceOf(FixedScoreFormat.class,
            updates.getFirst().getScoreFormat()).getValue());
    }

    @Test
    void aRemovedRowIsNotResurrectedByTheNextFormatRefresh() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        decorator.decorate(VIEWER, packet("entry-0", OBJECTIVE));
        decorator.removeScore(VIEWER, OBJECTIVE, "entry-0");
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        decorator.publish(VIEWER, fixed("13"), updates::add);

        assertEquals(List.of(), updates);
    }

    @Test
    void removingAnObjectiveForgetsItsRowsUntilTheyAreSentAgain() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        decorator.decorate(VIEWER, packet("entry-0", OBJECTIVE));
        decorator.removeObjective(VIEWER, OBJECTIVE);
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();
        decorator.publish(VIEWER, fixed("13"), updates::add);
        assertEquals(List.of(), updates);

        WrapperPlayServerUpdateScore replacement = packet("entry-0", OBJECTIVE);
        decorator.decorate(VIEWER, replacement);

        assertEquals(Component.text("13"), assertInstanceOf(FixedScoreFormat.class,
            replacement.getScoreFormat()).getValue());
    }

    @Test
    void foreignObjectivePacketsDoNotBecomeOwnedRows() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        decorator.decorate(VIEWER, packet("entry-0", "foreign"));
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        decorator.publish(VIEWER, fixed("13"), updates::add);

        assertEquals(List.of(), updates);
    }

    @Test
    void aFailedDeltaCanBeRetriedWithoutWaitingForAnotherValueChange() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        decorator.decorate(VIEWER, packet("entry-0", OBJECTIVE));
        assertThrows(IllegalStateException.class,
            () -> decorator.publish(VIEWER, fixed("13"), packet -> {
                throw new IllegalStateException("delivery failed");
            }));
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        decorator.publish(VIEWER, fixed("13"), updates::add);

        assertEquals(1, updates.size());
    }

    @Test
    void aReturningViewerStartsWithCurrentFormatsAndNoPreviousRows() {
        ScoreFormatDecorator decorator = decorator(fixed("12"));
        decorator.decorate(VIEWER, packet("entry-0", OBJECTIVE));
        decorator.forget(VIEWER);
        List<WrapperPlayServerUpdateScore> updates = new ArrayList<>();

        decorator.publish(VIEWER, fixed("13"), updates::add);
        assertEquals(List.of(), updates);
        WrapperPlayServerUpdateScore replacement = packet("entry-0", OBJECTIVE);
        decorator.decorate(VIEWER, replacement);
        assertEquals(Component.text("13"), assertInstanceOf(FixedScoreFormat.class,
            replacement.getScoreFormat()).getValue());

        decorator.publish(VIEWER, fixed("14"), updates::add);
        assertEquals(1, updates.size());
        assertEquals(Component.text("14"), assertInstanceOf(FixedScoreFormat.class,
            updates.getFirst().getScoreFormat()).getValue());
    }

    private static BoardFormatIndex fixed(String value) {
        return BoardFormatIndex.of(OBJECTIVE,
            Map.of("entry-0", ScoreFormat.fixedScore(Component.text(value))), false);
    }

    private static ScoreFormatDecorator decorator(BoardFormatIndex index) {
        ScoreFormatDecorator decorator = new ScoreFormatDecorator();
        if (index != null) {
            decorator.publish(VIEWER, index, packet -> {
                throw new AssertionError("A new board has no existing rows to refresh");
            });
        }
        return decorator;
    }

    private static WrapperPlayServerUpdateScore packet(String entry, String objective) {
        return new WrapperPlayServerUpdateScore(entry, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
            objective, Optional.of(1));
    }

}
