package art.arcane.gloss.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-message claim a chat prompt puts on a player. A claim that is never answered must expire
 * on its own, or the next thing the player says is swallowed by a prompt they have forgotten about.
 */
class ChatCaptureTest {

    private final UUID player = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(1_000L);

    @AfterEach
    void clear() {
        ChatCapture.clear();
        ChatCapture.clock(System::currentTimeMillis);
    }

    @Test
    void aClaimedMessageIsConsumedOnceAndHandedToItsListener() {
        List<String> seen = new ArrayList<>();
        ChatCapture.clock(clock::get);

        assertTrue(ChatCapture.claim(player, seen::add, 5_000L));
        assertTrue(ChatCapture.consume(player, "hello there"));
        assertEquals(List.of("hello there"), seen);

        assertFalse(ChatCapture.consume(player, "again"), "a claim answers exactly one message");
        assertEquals(List.of("hello there"), seen);
    }

    @Test
    void anUnclaimedPlayerIsLeftAlone() {
        assertFalse(ChatCapture.consume(player, "hello"));
    }

    @Test
    void aSecondClaimDoesNotDisplaceTheFirst() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        ChatCapture.clock(clock::get);

        assertTrue(ChatCapture.claim(player, first::add, 5_000L));
        assertFalse(ChatCapture.claim(player, second::add, 5_000L));

        ChatCapture.consume(player, "hello");
        assertEquals(List.of("hello"), first);
        assertEquals(List.of(), second);
    }

    @Test
    void aClaimPastItsWindowIsNotConsumed() {
        List<String> seen = new ArrayList<>();
        ChatCapture.clock(clock::get);
        ChatCapture.claim(player, seen::add, 5_000L);

        clock.addAndGet(5_001L);
        assertFalse(ChatCapture.consume(player, "too late"));
        assertEquals(List.of(), seen);
    }

    @Test
    void anExpiredClaimFreesThePlayerForANewOne() {
        List<String> seen = new ArrayList<>();
        ChatCapture.clock(clock::get);
        ChatCapture.claim(player, value -> {
        }, 5_000L);

        clock.addAndGet(5_001L);
        assertTrue(ChatCapture.claim(player, seen::add, 5_000L));
        assertTrue(ChatCapture.consume(player, "fresh"));
        assertEquals(List.of("fresh"), seen);
    }

    @Test
    void releasingACaptureDropsIt() {
        List<String> seen = new ArrayList<>();
        ChatCapture.claim(player, seen::add, 5_000L);
        ChatCapture.release(player);

        assertFalse(ChatCapture.consume(player, "hello"));
        assertEquals(List.of(), seen);
    }
}
