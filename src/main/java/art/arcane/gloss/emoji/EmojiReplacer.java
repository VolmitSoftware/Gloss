package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class EmojiReplacer {
    private static final int[] NO_CANDIDATES = new int[0];

    private final String[] ids;
    private final String[] tokens;
    private final String[] triggers;
    private final String[] values;
    private final ShowCondition[] conditions;
    private final Map<String, int[]> entriesById;
    private final int[] triggerEntries;
    private final int[] unindexedEntries;
    private final int longestId;
    private final boolean hasAnyTrigger;
    private final long[] triggerFirstAscii;
    private final char[] triggerFirstExtended;

    public EmojiReplacer(List<EmojiEntry> entries) {
        List<EmojiEntry> active = new ArrayList<>(entries.size());
        for (EmojiEntry entry : entries) {
            if (entry.enabled() && (entry.show().isAlwaysVisible() || entry.show().isDynamic())) {
                active.add(entry);
            }
        }

        int count = active.size();
        this.ids = new String[count];
        this.tokens = new String[count];
        this.triggers = new String[count];
        this.values = new String[count];
        this.conditions = new ShowCondition[count];
        Map<String, int[]> byId = new HashMap<>(Math.max(4, count * 2));
        int[] triggerList = new int[count];
        int triggerCount = 0;
        int[] unindexedList = new int[count];
        int unindexedCount = 0;
        int maxIdLength = 0;
        long[] ascii = new long[2];
        StringBuilder extended = new StringBuilder();
        for (int i = 0; i < count; i++) {
            EmojiEntry entry = active.get(i);
            ids[i] = entry.id();
            tokens[i] = entry.token();
            triggers[i] = entry.hasTrigger() ? entry.trigger() : null;
            values[i] = entry.emoji();
            conditions[i] = entry.show();
            // A token is ":id:", so an id carrying a colon cannot be found by the colon-pair scan;
            // those entries stay permanent candidates and are decided by the contains check.
            if (ids[i].indexOf(':') >= 0) {
                unindexedList[unindexedCount++] = i;
            } else {
                byId.merge(ids[i], new int[]{i}, EmojiReplacer::concat);
                maxIdLength = Math.max(maxIdLength, ids[i].length());
            }
            if (triggers[i] != null) {
                triggerList[triggerCount++] = i;
                char first = triggers[i].charAt(0);
                if (first < 128) {
                    ascii[first >>> 6] |= 1L << (first & 63);
                } else if (extended.indexOf(String.valueOf(first)) < 0) {
                    extended.append(first);
                }
            }
        }
        this.entriesById = Map.copyOf(byId);
        this.triggerEntries = Arrays.copyOf(triggerList, triggerCount);
        this.unindexedEntries = Arrays.copyOf(unindexedList, unindexedCount);
        this.longestId = maxIdLength;
        this.hasAnyTrigger = triggerCount > 0;
        this.triggerFirstAscii = ascii;
        this.triggerFirstExtended = extended.toString().toCharArray();
    }

    public String apply(String message) {
        return apply(message, null);
    }

    public String apply(String message, Predicate<String> idAllowed) {
        return apply(message, idAllowed, ShowCondition::isAlwaysVisible);
    }

    /**
     * Replaces the emoji the message actually references. The candidate scan decides which entries
     * the loop may touch, so the cost is one pass over the message plus one map lookup per colon
     * pair rather than two {@code contains} scans over the whole registry. The loop itself is the
     * original sequential replace, so replacement order effects are unchanged: after a replacement
     * the candidates are recomputed for the entries that have not run yet.
     */
    public String apply(String message, Predicate<String> idAllowed, Predicate<ShowCondition> visible) {
        if (message == null || message.isEmpty() || ids.length == 0) {
            return message == null ? "" : message;
        }

        String out = message;
        int[] candidates = candidates(out, 0);
        int position = 0;
        while (position < candidates.length) {
            int entry = candidates[position];
            String trigger = triggers[entry];
            boolean hasToken = out.contains(tokens[entry]);
            boolean hasTrigger = trigger != null && out.contains(trigger);
            if (!hasToken && !hasTrigger) {
                position++;
                continue;
            }
            if ((idAllowed != null && !idAllowed.test(ids[entry])) || !visible.test(conditions[entry])) {
                position++;
                continue;
            }

            if (hasTrigger) {
                out = out.replace(trigger, values[entry]);
            }
            if (hasToken) {
                out = out.replace(tokens[entry], values[entry]);
            }
            candidates = candidates(out, entry + 1);
            position = 0;
        }

        return out;
    }

    /**
     * Entry indexes at or above {@code minEntry} that may match {@code message}, ascending and
     * distinct. Exact superset: every {@code :id:} occurrence sits between two consecutive colons,
     * so walking colon pairs finds all of them, and triggers are gated on their first character
     * before any substring search runs.
     */
    int[] candidates(String message, int minEntry) {
        int[] found = null;
        int count = 0;
        int firstColon = message.indexOf(':');
        int open = firstColon;
        while (open >= 0) {
            int close = message.indexOf(':', open + 1);
            if (close < 0) {
                break;
            }
            if (close - open - 1 <= longestId) {
                int[] matches = entriesById.get(message.substring(open + 1, close));
                if (matches != null) {
                    for (int entry : matches) {
                        if (entry < minEntry) {
                            continue;
                        }
                        found = grow(found, count);
                        found[count++] = entry;
                    }
                }
            }
            open = close;
        }
        if (firstColon >= 0) {
            for (int entry : unindexedEntries) {
                if (entry < minEntry) {
                    continue;
                }
                found = grow(found, count);
                found[count++] = entry;
            }
        }
        if (hasAnyTrigger && containsTriggerFirst(message)) {
            for (int entry : triggerEntries) {
                if (entry < minEntry || message.indexOf(triggers[entry]) < 0) {
                    continue;
                }
                found = grow(found, count);
                found[count++] = entry;
            }
        }
        if (found == null) {
            return NO_CANDIDATES;
        }
        int[] result = Arrays.copyOf(found, count);
        Arrays.sort(result);
        return distinct(result);
    }

    private boolean containsTriggerFirst(String message) {
        int length = message.length();
        for (int i = 0; i < length; i++) {
            char value = message.charAt(i);
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

    private static int[] grow(int[] found, int count) {
        if (found == null) {
            return new int[4];
        }
        return count < found.length ? found : Arrays.copyOf(found, found.length * 2);
    }

    private static int[] distinct(int[] sorted) {
        int unique = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[unique++] = sorted[i];
            }
        }
        return unique == sorted.length ? sorted : Arrays.copyOf(sorted, unique);
    }

    private static int[] concat(int[] existing, int[] added) {
        int[] merged = Arrays.copyOf(existing, existing.length + added.length);
        System.arraycopy(added, 0, merged, existing.length, added.length);
        return merged;
    }
}
