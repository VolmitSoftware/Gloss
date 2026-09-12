package art.arcane.gloss.rig;

import java.util.Locale;

public enum PartType {
    BLOCK,
    ITEM,
    TEXT;

    public static PartType parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("rig part needs a type: block, item or text");
        }
        try {
            return valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("rig part type must be block, item or text, got '" + source + "'", failure);
        }
    }
}
