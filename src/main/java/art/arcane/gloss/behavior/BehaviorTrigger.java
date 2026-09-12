package art.arcane.gloss.behavior;

import java.util.Locale;
import java.util.Set;

/**
 * Every event a behavior entry can subscribe to, with the option keys that entry may carry
 * beside {@code when}, {@code permission} and {@code do}. {@code required} must be present;
 * anything outside {@code accepted} refuses the document so a typo never silently widens a filter.
 */
public enum BehaviorTrigger {
    JOIN("join", Set.of(), Set.of(), true),
    FIRST_JOIN("first_join", Set.of(), Set.of(), true),
    QUIT("quit", Set.of(), Set.of(), true),
    RESPAWN("respawn", Set.of(), Set.of(), true),
    DEATH("death", Set.of(), Set.of(), true),
    KILL("kill", Set.of(), Set.of(), true),
    DAMAGE("damage", Set.of(), Set.of(), true),
    CHAT("chat", Set.of("pattern"), Set.of(), true),
    COMMAND("command", Set.of("name"), Set.of("name"), true),
    BLOCK_BREAK("block_break", Set.of("material"), Set.of(), true),
    BLOCK_PLACE("block_place", Set.of("material"), Set.of(), true),
    PICKUP("pickup", Set.of("material"), Set.of(), true),
    DROP("drop", Set.of("material"), Set.of(), true),
    WORLD_CHANGE("world_change", Set.of(), Set.of(), true),
    REGION_ENTER("region_enter", Set.of("region"), Set.of("region"), true),
    REGION_LEAVE("region_leave", Set.of("region"), Set.of("region"), true),
    MENU_OPEN("menu_open", Set.of("menu"), Set.of(), true),
    MENU_CLOSE("menu_close", Set.of("menu"), Set.of(), true),
    MENU_CLICK("menu_click", Set.of("menu", "component"), Set.of(), true),
    DIALOG_SUBMIT("dialog_submit", Set.of("dialog"), Set.of(), true),
    INVENTORY_CLICK("inventory_click", Set.of("menu", "component"), Set.of(), true),
    INTERVAL("interval", Set.of("everyTicks", "scope"), Set.of("everyTicks"), false),
    SERVER_START("server_start", Set.of(), Set.of(), false),
    EMIT("emit", Set.of("name"), Set.of("name"), false);

    private final String key;
    private final Set<String> accepted;
    private final Set<String> required;
    private final boolean viewerAlwaysPresent;

    BehaviorTrigger(String key, Set<String> accepted, Set<String> required, boolean viewerAlwaysPresent) {
        this.key = key;
        this.accepted = accepted;
        this.required = required;
        this.viewerAlwaysPresent = viewerAlwaysPresent;
    }

    public static BehaviorTrigger parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("trigger is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BehaviorTrigger trigger : values()) {
            if (trigger.key.equals(normalized)) {
                return trigger;
            }
        }
        throw new IllegalArgumentException("unknown trigger \"" + value + "\"");
    }

    public String key() {
        return key;
    }

    public Set<String> accepted() {
        return accepted;
    }

    public Set<String> required() {
        return required;
    }

    /** False for the triggers that may fire without a player (global intervals, server start, API emits). */
    public boolean viewerAlwaysPresent() {
        return viewerAlwaysPresent;
    }
}
