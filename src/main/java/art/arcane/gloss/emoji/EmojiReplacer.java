package art.arcane.gloss.emoji;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class EmojiReplacer {
    private final String[] ids;
    private final String[] tokens;
    private final String[] triggers;
    private final String[] values;

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
        for (int i = 0; i < count; i++) {
            EmojiEntry entry = active.get(i);
            ids[i] = entry.id();
            tokens[i] = entry.token();
            triggers[i] = entry.hasTrigger() ? entry.trigger() : null;
            values[i] = entry.emoji();
        }
    }

    public String apply(String message) {
        return apply(message, null);
    }

    public String apply(String message, Predicate<String> idAllowed) {
        if (message == null || message.isEmpty() || ids.length == 0) {
            return message == null ? "" : message;
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
}
