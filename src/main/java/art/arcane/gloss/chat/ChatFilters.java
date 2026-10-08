package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import com.google.re2j.Matcher;

import java.util.function.LongSupplier;
import java.util.logging.Level;

public final class ChatFilters {
    private ChatFilters() {
    }

    public static String apply(ChannelRuntime channel, String message) {
        return apply(channel, message, System::nanoTime);
    }

    static String apply(ChannelRuntime channel, String message, LongSupplier nanoClock) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        if (channel.filters().isEmpty()) {
            return message;
        }
        ChannelDoc.Filtering limits = channel.doc().filtering();
        if (message.length() > limits.maxInputCharacters() || message.length() > limits.maxOutputCharacters()) {
            warn(channel, "input size", true);
            return null;
        }
        String current = message;
        Budget budget = new Budget(limits, nanoClock);
        for (int index = 0; index < channel.filters().size(); index++) {
            ChannelRuntime.CompiledFilter filter = channel.filters().get(index);
            try {
                current = replace(filter, current, budget);
            } catch (LimitExceeded limit) {
                boolean drop = limits.onLimit() == ChannelDoc.FilterLimitPolicy.DROP;
                warn(channel, limit.getMessage(), drop);
                return drop || current.trim().isEmpty() ? null : current;
            }
            if (budget.expired()) {
                if (index + 1 == channel.filters().size()) {
                    warn(channel, "elapsed budget exceeded after completing all filters", false);
                    break;
                }
                boolean drop = limits.onLimit() == ChannelDoc.FilterLimitPolicy.DROP;
                warn(channel, "elapsed budget", drop);
                return drop || current.trim().isEmpty() ? null : current;
            }
        }
        return current.trim().isEmpty() ? null : current;
    }

    private static String replace(ChannelRuntime.CompiledFilter filter, String input, Budget budget) {
        Matcher matcher = filter.pattern().matcher(input);
        StringBuilder result = null;
        int cursor = 0;
        int nextSearch = 0;
        while (true) {
            budget.admit(filter, input.length() - cursor);
            if (nextSearch > input.length() || !matcher.find(nextSearch)) {
                break;
            }
            budget.match();
            if (result == null) {
                result = new StringBuilder(Math.min(input.length(), budget.limits.maxOutputCharacters()));
            }
            int unchanged = matcher.start() - cursor;
            long nextLength = (long) result.length() + unchanged + filter.replace().length();
            if (nextLength > budget.limits.maxOutputCharacters()) {
                throw new LimitExceeded("output size");
            }
            result.append(input, cursor, matcher.start());
            result.append(filter.replace());
            cursor = matcher.end();
            nextSearch = cursor;
            if (matcher.start() == cursor) {
                nextSearch += cursor < input.length() ? Character.charCount(input.codePointAt(cursor)) : 1;
            }
        }
        if (result == null) {
            return input;
        }
        if ((long) result.length() + input.length() - cursor > budget.limits.maxOutputCharacters()) {
            throw new LimitExceeded("output size");
        }
        return result.append(input, cursor, input.length()).toString();
    }

    private static void warn(ChannelRuntime channel, String reason, boolean dropped) {
        Gloss.logThrottled(Level.WARNING, "chat-filter-limit:" + channel.id(),
            "Channel %s reached its filter %s; %s.", channel.id(), reason,
            dropped ? "message dropped" : "only fully completed filters were retained");
    }

    private static final class Budget {
        private final ChannelDoc.Filtering limits;
        private final LongSupplier clock;
        private final long started;
        private long work;
        private int matches;

        private Budget(ChannelDoc.Filtering limits, LongSupplier clock) {
            this.limits = limits;
            this.clock = clock;
            this.started = clock.getAsLong();
        }

        private void admit(ChannelRuntime.CompiledFilter filter, int remaining) {
            long requested = (long) filter.pattern().programSize() * (remaining + 1L);
            if (requested > limits.maxWorkUnits() - work) {
                throw new LimitExceeded("work limit");
            }
            work += requested;
        }

        private void match() {
            if (++matches > limits.maxMatches()) {
                throw new LimitExceeded("match count");
            }
            if ((matches & 63) == 0 && expired()) {
                throw new LimitExceeded("elapsed budget");
            }
        }

        private boolean expired() {
            return clock.getAsLong() - started >= limits.budgetMicros() * 1000L;
        }
    }

    private static final class LimitExceeded extends RuntimeException {
        private LimitExceeded(String reason) {
            super(reason, null, false, false);
        }
    }
}
