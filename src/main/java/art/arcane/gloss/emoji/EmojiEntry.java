package art.arcane.gloss.emoji;

public record EmojiEntry(String id, String trigger, String emoji, boolean enabled) {
    public boolean hasTrigger() {
        return trigger != null && !trigger.isEmpty();
    }

    public String token() {
        return ":" + id + ":";
    }
}
