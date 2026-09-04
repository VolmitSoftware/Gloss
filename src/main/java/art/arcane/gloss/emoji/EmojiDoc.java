package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

public record EmojiDoc(int schemaVersion, long revision, String trigger, String emoji, Boolean enabled, ShowCondition show) {
    public static final String KIND = "emoji";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public EmojiDoc {
        show = show == null ? ShowCondition.ALWAYS : show;
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        trigger = trigger == null ? "" : trigger;
        enabled = enabled == null ? Boolean.TRUE : enabled;
        if (emoji == null || emoji.isBlank()) {
            throw new IllegalArgumentException("emoji document requires an emoji value");
        }
    }

    public static EmojiDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, EmojiDoc.class);
    }
}
