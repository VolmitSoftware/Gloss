package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.util.common.StubPacketEventsApi;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardObjectiveRendererTest {
    @BeforeAll
    static void install() {
        StubPacketEventsApi.install();
    }

    @AfterAll
    static void clear() {
        StubPacketEventsApi.clear();
    }

    @Test
    void nativeSlotsCreateDiffAndReleaseWithoutTouchingSidebar() {
        BoardObjectiveRenderer renderer = renderer();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        BoardObjectiveRenderer.Frame first = frame("yield", Map.of("PlayerOne", value(10)));
        assertEquals(3, renderer.apply(first, packets::add));
        assertEquals(0, assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, packets.getLast()).getPosition());
        packets.clear();
        assertEquals(0, renderer.apply(first, packets::add));
        BoardObjectiveRenderer.Frame changed = frame("yield", Map.of("PlayerOne", value(12)));
        assertEquals(1, renderer.apply(changed, packets::add));
        WrapperPlayServerUpdateScore update = assertInstanceOf(WrapperPlayServerUpdateScore.class, packets.getFirst());
        assertEquals("PlayerOne", update.getEntityName());
        assertEquals(12, update.getValue().orElseThrow());
        packets.clear();
        assertEquals(2, renderer.apply(null, packets::add));
        assertEquals("", assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, packets.getFirst()).getScoreName());
        assertEquals(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
            assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.getLast()).getMode());
    }

    @Test
    void yieldPreservesExistingForeignSlotAndClaimsItOnlyWhenReleased() {
        BoardObjectiveRenderer renderer = renderer();
        renderer.displayed(0, "foreign");
        List<PacketWrapper<?>> packets = new ArrayList<>();
        assertEquals(0, renderer.apply(frame("yield", Map.of()), packets::add));
        renderer.removed("foreign");
        assertEquals(2, renderer.apply(frame("yield", Map.of()), packets::add));
    }

    @Test
    void foreignTakeoverMakesYieldModeRemoveOnlyItsOwnObjective() {
        BoardObjectiveRenderer renderer = renderer();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        renderer.apply(frame("yield", Map.of()), packets::add);
        renderer.displayed(0, "replacement");
        packets.clear();
        assertEquals(1, renderer.apply(frame("yield", Map.of()), packets::add));
        assertEquals(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
            assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.getFirst()).getMode());
    }

    @Test
    void overrideRestoresThePreviousForeignObjectiveOnRelease() {
        BoardObjectiveRenderer renderer = renderer();
        renderer.displayed(0, "previous");
        List<PacketWrapper<?>> packets = new ArrayList<>();
        renderer.apply(frame("override", Map.of()), packets::add);
        packets.clear();
        renderer.apply(null, packets::add);
        assertEquals("previous", assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, packets.getFirst()).getScoreName());
    }

    @Test
    void removedForeignObjectiveIsNeverRestored() {
        BoardObjectiveRenderer renderer = renderer();
        renderer.displayed(0, "previous");
        List<PacketWrapper<?>> packets = new ArrayList<>();
        renderer.apply(frame("override", Map.of()), packets::add);
        renderer.removed("previous");
        packets.clear();
        renderer.apply(null, packets::add);
        assertEquals("", assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, packets.getFirst()).getScoreName());
    }

    @Test
    void unknownOwnershipYieldsAndUnchangedNumericEntriesAreNotResent() {
        BoardObjectiveRenderer renderer = new BoardObjectiveRenderer();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        assertEquals(0, renderer.apply(frame("yield", Map.of()), packets::add));
        renderer.displayed(0, "");
        renderer.apply(frame("yield", Map.of("PlayerOne", value(10), "PlayerTwo", value(20))), packets::add);
        packets.clear();
        assertEquals(1, renderer.apply(frame("yield", Map.of("PlayerOne", value(10))), packets::add));
        assertInstanceOf(WrapperPlayServerResetScore.class, packets.getFirst());
    }

    @Test
    void belowNameUsesNativeSlotTwoAndHeartsRenderType() {
        BoardObjectiveRenderer renderer = renderer();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        BoardObjectives.Slot slot = slot("{\"renderType\":\"hearts\"}");
        renderer.apply(new BoardObjectiveRenderer.Frame(null,
            new BoardObjectiveRenderer.Prepared(slot, Component.text("Health"), Map.of("PlayerOne", value(20)))), packets::add);
        assertEquals(WrapperPlayServerScoreboardObjective.RenderType.HEARTS,
            assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.getFirst()).getRenderType());
        assertEquals(2, assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, packets.getLast()).getPosition());
    }

    @Test
    void partialScoreSendFailureRetriesWithoutRecreatingObjective() {
        BoardObjectiveRenderer renderer = renderer();
        BoardObjectiveRenderer.Frame frame = frame("yield", Map.of("PlayerOne", value(12)));
        assertThrows(IllegalStateException.class, () -> renderer.apply(frame, packet -> {
            if (packet instanceof WrapperPlayServerUpdateScore) {
                throw new IllegalStateException("closed");
            }
        }));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        renderer.apply(frame, packets::add);
        assertEquals(2, packets.size());
        assertInstanceOf(WrapperPlayServerUpdateScore.class, packets.getFirst());
        assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, packets.getLast());
    }

    @Test
    void nativeDefinitionsRoundTripAndNumericBoundsAreExplicit() {
        BoardDoc document = BoardDoc.parse("native.json", """
            {"schemaVersion":2,"revision":3,"objectives":{
              "playerList":{"title":"Points","value":"papiNumber('subject', '%points%', 0)","refreshTicks":10},
              "belowName":{"title":"Health","value":"subject.health","renderType":"hearts"}}}
            """);
        GlossBoardMeta meta = GlossBoardMeta.fromDoc("native", document);
        assertTrue(meta.usesFastRefreshText());
        assertTrue(meta.usesFastRefresh(false));
        BoardDoc roundtrip = BoardDoc.parse("native.json", DocumentParsers.GSON.toJson(meta.toDoc(4)));
        assertFalse(roundtrip.objectives().isEmpty());
        assertEquals(10, roundtrip.objectives().playerList().refreshTicks());
        assertEquals(4, roundtrip.revision());
        assertEquals(13, BoardNativeObjectives.score(12.6));
        assertEquals(Integer.MAX_VALUE, BoardNativeObjectives.score(1e20));
        assertEquals(Integer.MIN_VALUE, BoardNativeObjectives.score(-1e20));
        assertThrows(IllegalArgumentException.class, () -> BoardNativeObjectives.score(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> slot("{\"refreshTicks\":0}"));
        assertThrows(IllegalArgumentException.class, () -> slot("{\"renderType\":\"text\"}"));
    }

    @Test
    void aLateOwnershipSeedCannotOverwriteAnObservedDisplayPacket() {
        BoardObjectiveRenderer renderer = new BoardObjectiveRenderer();
        renderer.displayed(0, "live-foreign");
        renderer.seedIfUnknown(0, "");
        List<PacketWrapper<?>> packets = new ArrayList<>();
        assertEquals(0, renderer.apply(frame("yield", Map.of()), packets::add));
    }

    @Test
    void retiredSubjectCallbacksCannotPublishOrClearRejoinedSamples() {
        BoardNativeObjectives.Samples samples = new BoardNativeObjectives.Samples(
            BoardNativeObjectives.Source.of(slot("{}")));
        UUID player = UUID.randomUUID();
        BoardNativeObjectives.SampleTicket retired = new BoardNativeObjectives.SampleTicket(null);
        samples.pending.put(player, retired);
        samples.forget(player);
        BoardNativeObjectives.SampleTicket current = new BoardNativeObjectives.SampleTicket(null);
        samples.pending.put(player, current);
        samples.update(player, retired, new BoardNativeObjectives.SubjectValue("PlayerOne", value(1)));
        assertTrue(samples.snapshot().isEmpty());
        assertFalse(samples.pending.remove(player, retired));
        samples.update(player, current, new BoardNativeObjectives.SubjectValue("PlayerOne", value(2)));
        assertEquals(Map.of("PlayerOne", value(2)), samples.snapshot());
    }

    private static BoardObjectiveRenderer renderer() {
        BoardObjectiveRenderer renderer = new BoardObjectiveRenderer();
        renderer.displayed(0, "");
        renderer.displayed(2, "");
        return renderer;
    }

    private static BoardObjectiveRenderer.Frame frame(String conflict, Map<String, BoardObjectiveRenderer.Value> values) {
        return new BoardObjectiveRenderer.Frame(new BoardObjectiveRenderer.Prepared(
            slot("{\"conflict\":\"" + conflict + "\"}"), Component.text("Points"), values), null);
    }

    private static BoardObjectives.Slot slot(String json) {
        return DocumentParsers.parseJson("objective", json, BoardObjectives.Slot.class);
    }

    private static BoardObjectiveRenderer.Value value(int score) {
        return new BoardObjectiveRenderer.Value(score, BoardLineFormat.NUMBER, "");
    }
}
