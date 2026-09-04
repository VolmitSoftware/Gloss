package art.arcane.gloss.emoji;

import art.arcane.gloss.condition.ShowCondition;

public record EmojiEntry(String id, String trigger, String emoji, boolean enabled, ShowCondition show) {
    public EmojiEntry {
        show = show == null ? ShowCondition.ALWAYS : show;
    }

    public boolean hasTrigger() {
        return trigger != null && !trigger.isEmpty();
    }

    public String token() {
        return ":" + id + ":";
    }
}
