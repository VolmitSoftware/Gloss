package art.arcane.gloss.chat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A one-message claim on a player's chat. A chat prompt claims, the chat listeners ask here before
 * doing anything else, and the claim is answered exactly once. Claims expire on their own so a
 * prompt the player walked away from cannot swallow a message minutes later.
 */
public final class ChatCapture {
    private static final ConcurrentMap<UUID, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private ChatCapture() {
    }

    /** @return false when this player already has a live claim */
    public static boolean claim(UUID player, Consumer<String> consumer, long timeoutMs) {
        if (player == null || consumer == null) {
            return false;
        }
        long now = clock.getAsLong();
        Claim claim = new Claim(consumer, now + Math.max(1L, timeoutMs));
        Claim previous = CLAIMS.putIfAbsent(player, claim);
        if (previous == null) {
            return true;
        }
        if (previous.expiresAtMs() > now) {
            return false;
        }
        return CLAIMS.replace(player, previous, claim);
    }

    /** @return true when the message was claimed, in which case the caller cancels the event */
    public static boolean consume(UUID player, String message) {
        Claim claim = CLAIMS.get(player);
        if (claim == null) {
            return false;
        }
        if (claim.expiresAtMs() <= clock.getAsLong()) {
            CLAIMS.remove(player, claim);
            return false;
        }
        if (!CLAIMS.remove(player, claim)) {
            return false;
        }
        claim.consumer().accept(message == null ? "" : message);
        return true;
    }

    public static void release(UUID player) {
        CLAIMS.remove(player);
    }

    /** Test seam: the clock claims expire against. */
    public static void clock(LongSupplier source) {
        clock = source == null ? System::currentTimeMillis : source;
    }

    public static void clear() {
        CLAIMS.clear();
    }

    private record Claim(Consumer<String> consumer, long expiresAtMs) {
    }
}
