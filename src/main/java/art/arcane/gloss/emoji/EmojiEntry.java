package art.arcane.gloss.emoji;

public record EmojiEntry(String id, String trigger, String emoji, boolean enabled) {
    public static final String NO_TRIGGER = "<uses :id:>";

    public boolean hasTrigger() {
        return trigger != null && !trigger.isEmpty() && !NO_TRIGGER.equals(trigger);
    }

    public String token() {
        return ":" + id + ":";
    }
}
