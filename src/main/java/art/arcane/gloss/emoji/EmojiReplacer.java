package art.arcane.gloss.emoji;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class EmojiReplacer {
    private final String[] ids;
    private final String[] tokens;
    private final String[] triggers;
    private final String[] values;
    private final boolean hasAnyTrigger;
    private final long[] triggerFirstAscii;
    private final char[] triggerFirstExtended;

    public EmojiReplacer(List<EmojiEntry> entries) {
        List<EmojiEntry> active = new ArrayList<>(entries.size());
        for (EmojiEntry entry : entries) {
            if (entry.enabled()) {
                active.add(entry);
            }
        }

        int count = active.size();
        this.ids = new String[count];
        this.tokens = new String[count];
        this.triggers = new String[count];
        this.values = new String[count];
        long[] ascii = new long[2];
        StringBuilder extended = new StringBuilder();
        boolean anyTrigger = false;
        for (int i = 0; i < count; i++) {
            EmojiEntry entry = active.get(i);
            ids[i] = entry.id();
            tokens[i] = entry.token();
            triggers[i] = entry.hasTrigger() ? entry.trigger() : null;
            values[i] = entry.emoji();
            if (triggers[i] != null) {
                anyTrigger = true;
                char first = triggers[i].charAt(0);
                if (first < 128) {
                    ascii[first >>> 6] |= 1L << (first & 63);
                } else if (extended.indexOf(String.valueOf(first)) < 0) {
                    extended.append(first);
                }
            }
        }
        this.hasAnyTrigger = anyTrigger;
        this.triggerFirstAscii = ascii;
        this.triggerFirstExtended = extended.toString().toCharArray();
    }

    public String apply(String message) {
        return apply(message, null);
    }

    public String apply(String message, Predicate<String> idAllowed) {
        if (message == null || message.isEmpty() || ids.length == 0) {
            return message == null ? "" : message;
        }
        if (!mightMatch(message)) {
            return message;
        }

        String out = message;
        for (int i = 0; i < ids.length; i++) {
            String trigger = triggers[i];
            boolean hasToken = out.contains(tokens[i]);
            boolean hasTrigger = trigger != null && out.contains(trigger);
            if (!hasToken && !hasTrigger) {
                continue;
            }
            if (idAllowed != null && !idAllowed.test(ids[i])) {
                continue;
            }

            if (hasTrigger) {
                out = out.replace(trigger, values[i]);
            }
            if (hasToken) {
                out = out.replace(tokens[i], values[i]);
            }
        }

        return out;
    }

    /**
     * Exact prefilter: every token is {@code :id:}, so a message without a colon cannot
     * contain a token, and a message without any trigger's first character cannot contain
     * a trigger. False only when no entry can possibly match.
     */
    private boolean mightMatch(String message) {
        if (!hasAnyTrigger) {
            return message.indexOf(':') >= 0;
        }
        int length = message.length();
        for (int i = 0; i < length; i++) {
            char value = message.charAt(i);
            if (value == ':') {
                return true;
            }
            if (value < 128) {
                if ((triggerFirstAscii[value >>> 6] & (1L << (value & 63))) != 0L) {
                    return true;
                }
                continue;
            }
            for (char candidate : triggerFirstExtended) {
                if (candidate == value) {
                    return true;
                }
            }
        }
        return false;
    }
}
