package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;

import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * The authored word filters, applied to the plain message before anything renders. Filters are
 * precompiled patterns; a channel whose patterns backtrack past the budget stops there and keeps
 * the text it already produced, so one pathological pattern cannot stall the chat thread.
 */
public final class ChatFilters {
    public static final long BUDGET_NANOS = 2_000_000L;

    private ChatFilters() {
    }

    /** @return the filtered message, or null when the filters emptied it */
    public static String apply(ChannelRuntime channel, String message) {
        return apply(channel, message, System::nanoTime);
    }

    static String apply(ChannelRuntime channel, String message, LongSupplier nanoClock) {
        if (message == null || message.isEmpty() || channel.filters().isEmpty()) {
            return message == null || message.trim().isEmpty() ? null : message;
        }
        String current = message;
        long start = nanoClock.getAsLong();
        for (ChannelRuntime.CompiledFilter filter : channel.filters()) {
            current = filter.pattern().matcher(current).replaceAll(java.util.regex.Matcher
                .quoteReplacement(filter.replace()));
            if (nanoClock.getAsLong() - start >= BUDGET_NANOS) {
                Gloss.logThrottled(Level.WARNING, "chat-filter-budget:" + channel.id(),
                    "Channel %s filters exceeded the %d us budget; remaining filters skipped.",
                    channel.id(), BUDGET_NANOS / 1000L);
                break;
            }
        }
        return current.trim().isEmpty() ? null : current;
    }
}
