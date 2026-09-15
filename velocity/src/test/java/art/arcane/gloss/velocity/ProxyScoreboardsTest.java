package art.arcane.gloss.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.channel.ChannelOperator;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyScoreboardsTest {
    private final List<PacketWrapper<?>> packets = new ArrayList<>();
    private PacketEventsAPI<?> previousApi;
    private ProxyScoreboards boards;
    private Player player;
    private User user;

    @BeforeEach
    void prepare() {
        previousApi = PacketEvents.getAPI();
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        PacketEvents.setAPI(api);
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_11);
        player = mock(Player.class);
        user = mock(User.class);
        UUID id = UUID.randomUUID();
        Object channel = new Object();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isActive()).thenReturn(true);
        when(user.getUUID()).thenReturn(id);
        when(user.getChannel()).thenReturn(channel);
        when(user.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        when(user.getEncoderState()).thenReturn(ConnectionState.PLAY);
        when(api.getPlayerManager().getUser(player)).thenReturn(user);
        ChannelOperator operator = api.getNettyManager().getChannelOperator();
        when(operator.isOpen(channel)).thenReturn(true);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(operator).runInEventLoop(eq(channel), any(Runnable.class));
        doAnswer(invocation -> {
            packets.add(invocation.getArgument(0));
            return null;
        }).when(user).sendPacketSilently(any(PacketWrapper.class));
        boards = new ProxyScoreboards(mock(Logger.class));
    }

    @AfterEach
    void cleanup() {
        boards.close();
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void unchangedFramesSendNoPacketsAndChangedLinesKeepTheirIdentity() {
        List<ProxyScoreboards.Line> lines = List.of(line("same"), line("same"));
        boards.render(player, Component.text("Title"), lines, true);
        List<WrapperPlayServerUpdateScore> scores = scores();
        assertEquals(2, scores.size());
        assertEquals("gloss_line_0", scores.get(0).getEntityName());
        assertEquals("gloss_line_1", scores.get(1).getEntityName());
        packets.clear();
        boards.render(player, Component.text("Title"), lines, true);
        assertTrue(packets.isEmpty());
        boards.render(player, Component.text("Title"), List.of(line("changed"), line("same")), true);
        assertEquals(1, packets.size());
        assertEquals("gloss_line_0", scores().getFirst().getEntityName());
    }

    @Test
    void shrinkingRowsResetsRemovedScoresAndRenumbersSurvivors() {
        boards.render(player, Component.text("Title"), List.of(line("a"), line("b")), false);
        packets.clear();
        boards.render(player, Component.text("Title"), List.of(line("a")), false);
        assertEquals(2, packets.size());
        assertEquals(1, scores().getFirst().getValue().orElseThrow());
        WrapperPlayServerResetScore reset = assertInstanceOf(WrapperPlayServerResetScore.class, packets.get(1));
        assertEquals("gloss_line_1", reset.getTargetName());
        assertEquals("gloss_proxy", reset.getObjective());
    }

    @Test
    void objectiveAndLineFormatsAreIndependent() {
        ScoreFormat fixed = ScoreFormat.fixedScore(Component.text("42 coins"));
        boards.render(player, Component.text("Title"), List.of(new ProxyScoreboards.Line(Component.text("Balance"), fixed)), true);
        WrapperPlayServerScoreboardObjective objective = assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.get(1));
        assertSame(ScoreFormat.blankScore(), objective.getScoreFormat());
        assertSame(fixed, scores().getFirst().getScoreFormat());
        packets.clear();
        boards.render(player, Component.text("Title"), List.of(new ProxyScoreboards.Line(
                Component.text("Balance"), ScoreFormat.fixedScore(Component.text("42 coins")))), true);
        assertTrue(packets.isEmpty());
        boards.render(player, Component.text("Title"), List.of(line("Balance")), true);
        assertEquals(1, packets.size());
        assertNull(scores().getFirst().getScoreFormat());
    }

    @Test
    void configurationDefersPacketsAndResetRecreatesTheObjective() {
        when(user.getEncoderState()).thenReturn(ConnectionState.CONFIGURATION);
        boards.render(player, Component.text("Title"), List.of(line("a")), false);
        assertTrue(packets.isEmpty());
        when(user.getEncoderState()).thenReturn(ConnectionState.PLAY);
        boards.render(player, Component.text("Title"), List.of(line("a")), false);
        assertEquals(1, scores().size());
        packets.clear();
        boards.reset(player);
        WrapperPlayServerScoreboardObjective remove = assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.get(0));
        WrapperPlayServerScoreboardObjective create = assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.get(1));
        assertEquals(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, remove.getMode());
        assertEquals(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, create.getMode());
    }

    @Test
    void clearRemovesTheObjectiveAndUnsupportedClientsReceiveNothing() {
        boards.render(player, Component.text("Title"), List.of(line("a")), true);
        packets.clear();
        boards.clear(player);
        assertEquals(1, packets.size());
        WrapperPlayServerScoreboardObjective remove = assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.getFirst());
        assertEquals(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, remove.getMode());
        packets.clear();
        when(user.getClientVersion()).thenReturn(ClientVersion.V_1_20_2);
        boards.render(player, Component.text("Title"), List.of(line("a")), false);
        assertTrue(packets.isEmpty());
    }

    @Test
    void backendSidebarsAreSuppressedWhileOwnedAndRestoredOnClear() {
        PacketSendEvent initial = displayEvent(1, "backend_before");
        boards.onPacketSend(initial);
        verify(initial, never()).setCancelled(true);
        boards.render(player, Component.text("Title"), List.of(line("a")), true);
        PacketSendEvent backend = displayEvent(1, "backend_after");
        boards.onPacketSend(backend);
        verify(backend).setCancelled(true);
        PacketSendEvent colored = displayEvent(5, "team_sidebar");
        boards.onPacketSend(colored);
        verify(colored).setCancelled(true);
        PacketSendEvent belowName = displayEvent(2, "health");
        boards.onPacketSend(belowName);
        verify(belowName, never()).setCancelled(true);
        packets.clear();
        boards.clear(player);
        assertEquals(3, packets.size());
        List<String> restored = new ArrayList<>();
        for (PacketWrapper<?> packet : packets) {
            if (packet instanceof WrapperPlayServerDisplayScoreboard display) {
                restored.add(display.getScoreName());
            }
        }
        assertTrue(restored.contains("backend_after"));
        assertTrue(restored.contains("team_sidebar"));
    }

    @Test
    void backendCannotRemoveTheOwnedObjective() {
        boards.render(player, Component.text("Title"), List.of(line("a")), true);
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(event.getPacketType()).thenReturn(PacketType.Play.Server.SCOREBOARD_OBJECTIVE);
        doReturn(new WrapperPlayServerScoreboardObjective("gloss_proxy",
                WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, Component.empty(), null))
                .when(event).getLastUsedWrapper();
        boards.onPacketSend(event);
        verify(event).setCancelled(true);
    }

    private PacketSendEvent displayEvent(int slot, String objective) {
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(event.getPacketType()).thenReturn(PacketType.Play.Server.DISPLAY_SCOREBOARD);
        doReturn(new WrapperPlayServerDisplayScoreboard(slot, objective)).when(event).getLastUsedWrapper();
        return event;
    }

    private List<WrapperPlayServerUpdateScore> scores() {
        List<WrapperPlayServerUpdateScore> scores = new ArrayList<>();
        for (PacketWrapper<?> packet : packets) {
            if (packet instanceof WrapperPlayServerUpdateScore score) {
                scores.add(score);
            }
        }
        return scores;
    }

    private static ProxyScoreboards.Line line(String text) {
        return new ProxyScoreboards.Line(Component.text(text), null);
    }
}
