package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Lets a glyph pack stand in for the unicode an emoji normally renders as. The hook is null until
 * the forge lane maps an emoji id to a glyph, so emoji behave exactly as before without it, and
 * {@link #appliesTo} is asked once per message so a viewer without the pack costs one call.
 */
public interface GlyphSubstitution {
    boolean appliesTo(Player viewer);

    /** @return the glyph string for this emoji id, or empty when the id has no glyph */
    Optional<String> glyphFor(String emojiId);

    /**
     * Replaces the tokens and triggers of every emoji that has a glyph, leaving the rest for the
     * unicode replacer. The permission and visibility gates are the caller's, so a glyph never
     * renders where the unicode would not have.
     */
    static String apply(GlyphSubstitution hook, Player viewer, String message, List<EmojiEntry> entries,
                        Predicate<String> idAllowed, Predicate<ShowCondition> visible) {
        if (hook == null || message == null || message.isEmpty() || !hook.appliesTo(viewer)) {
            return message == null ? "" : message;
        }
        String out = message;
        for (EmojiEntry entry : entries) {
            if (!entry.enabled()) {
                continue;
            }
            Optional<String> glyph = hook.glyphFor(entry.id());
            if (glyph.isEmpty()) {
                continue;
            }
            if ((idAllowed != null && !idAllowed.test(entry.id())) || !visible.test(entry.show())) {
                continue;
            }
            if (entry.hasTrigger()) {
                out = out.replace(entry.trigger(), glyph.get());
            }
            out = out.replace(entry.token(), glyph.get());
        }
        return out;
    }
}
