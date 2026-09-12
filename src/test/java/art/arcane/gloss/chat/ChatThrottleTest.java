package art.arcane.gloss.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatThrottleTest {
    private static final UUID SENDER = UUID.randomUUID();
    private static final ChannelDoc.Throttle SETTINGS = new ChannelDoc.Throttle(100, 1, 10);

    @Test
    void theFirstMessageIsAlwaysAccepted() {
        assertEquals(ChatThrottle.Verdict.OK, new ChatThrottle().check(SENDER, "hello", 0L, SETTINGS));
    }

    @Test
    void aSecondMessageInsideTheMinimumIntervalIsTooFast() {
        ChatThrottle throttle = new ChatThrottle();
        throttle.check(SENDER, "hello", 0L, SETTINGS);

        assertEquals(ChatThrottle.Verdict.TOO_FAST, throttle.check(SENDER, "different", 400L, SETTINGS));
        assertEquals(ChatThrottle.Verdict.OK, throttle.check(SENDER, "different", 500L, SETTINGS));
    }

    @Test
    void theSameTextBeyondMaxRepeatsInsideTheWindowIsARepeat() {
        ChatThrottle throttle = new ChatThrottle();
        throttle.check(SENDER, "hello", 0L, SETTINGS);

        assertEquals(ChatThrottle.Verdict.REPEAT, throttle.check(SENDER, "hello", 600L, SETTINGS));
        assertEquals(ChatThrottle.Verdict.OK, throttle.check(SENDER, "hello", 6000L, SETTINGS));
    }

    @Test
    void aRejectedMessageDoesNotRestartTheInterval() {
        ChatThrottle throttle = new ChatThrottle();
        throttle.check(SENDER, "hello", 0L, SETTINGS);
        throttle.check(SENDER, "second", 100L, SETTINGS);

        assertEquals(ChatThrottle.Verdict.OK, throttle.check(SENDER, "second", 500L, SETTINGS));
    }

    @Test
    void zeroedSettingsDisableBothGuards() {
        ChatThrottle throttle = new ChatThrottle();
        ChannelDoc.Throttle off = new ChannelDoc.Throttle(0, 1, 0);
        throttle.check(SENDER, "hello", 0L, off);

        assertEquals(ChatThrottle.Verdict.OK, throttle.check(SENDER, "hello", 0L, off));
    }

    @Test
    void higherMaxRepeatsAllowsMoreOfTheSameText() {
        ChatThrottle throttle = new ChatThrottle();
        ChannelDoc.Throttle lenient = new ChannelDoc.Throttle(100, 3, 0);
        throttle.check(SENDER, "hello", 0L, lenient);
        throttle.check(SENDER, "hello", 10L, lenient);

        assertEquals(ChatThrottle.Verdict.OK, throttle.check(SENDER, "hello", 20L, lenient));
        assertEquals(ChatThrottle.Verdict.REPEAT, throttle.check(SENDER, "hello", 30L, lenient));
    }

    @Test
    void forgettingASenderClearsTheirHistory() {
        ChatThrottle throttle = new ChatThrottle();
        throttle.check(SENDER, "hello", 0L, SETTINGS);
        throttle.forget(SENDER);

        assertEquals(ChatThrottle.Verdict.OK, throttle.check(SENDER, "hello", 1L, SETTINGS));
    }
}
