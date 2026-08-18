package art.arcane.gloss.emoji;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.io.FolderWatcher;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONObject;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class EmojiService {
    private static final String PERMISSION_PREFIX = "gloss.emoji.";
    private static final String[][] DEFAULTS = {
        {"heart", "<3", "U+2764;"},
        {"airplane", null, "U+2708;"},
        {"mail", null, "U+2709;"},
        {"cut", null, "U+2702;"},
        {"pencil", null, "U+270F;"},
        {"nib", null, "U+2712;"},
        {"check", null, "U+2713;"},
        {"thickcheck", null, "U+2714;"},
        {"cross", null, "U+2715;"},
        {"thickcross", null, "U+2716;"},
        {"star", null, "U+2733;"},
        {"darkstarstar", null, "U+2734;"},
        {"snowflake", null, "U+2744;"},
        {"sparkle", null, "U+2747;"},
        {"sun", null, "U+263C;"},
        {"left", null, "U+2190;"},
        {"right", null, "U+2192;"},
        {"up", null, "U+2191;"},
        {"down", null, "U+2193;"},
        {"leftright", null, "U+2194;"},
        {"updown", null, "U+2195;"},
        {"upleft", null, "U+2196;"},
        {"upright", null, "U+2197;"},
        {"downright", null, "U+2198;"},
        {"downleft", null, "U+2199;"},
        {"tapedrive", null, "U+2707;"},
        {"thickplus", null, "U+271A;"},
        {"lineplus", null, "U+2719;"},
        {"opencross", null, "U+271B;"},
        {"thickopencross", null, "U+271C;"},
        {"maltcross", null, "U+2720;"},
        {"starofdavid", null, "U+2721;"},
        {"heavystar", null, "U+2731;"},
        {"blackdiamond", null, "U+2756;"},
        {"vbar1", null, "U+2758;"},
        {"vbar2", null, "U+2759;"},
        {"vbar3", null, "U+275A;"},
        {"1", null, "U+278A;"},
        {"2", null, "U+278B;"},
        {"3", null, "U+278C;"},
        {"4", null, "U+278D;"},
        {"5", null, "U+278E;"},
        {"6", null, "U+278F;"},
        {"7", null, "U+2790;"},
        {"8", null, "U+2791;"},
        {"9", null, "U+2792;"},
        {"10", null, "U+2793;"},
        {"1t", null, "U+2776;"},
        {"2t", null, "U+2777;"},
        {"3t", null, "U+2778;"},
        {"4t", null, "U+2779;"},
        {"5t", null, "U+277A;"},
        {"6t", null, "U+277B;"},
        {"7t", null, "U+277C;"},
        {"8t", null, "U+277D;"},
        {"9t", null, "U+277E;"},
        {"10t", null, "U+277F;"},
        {"1h", null, "U+2780;"},
        {"2h", null, "U+2781;"},
        {"3h", null, "U+2782;"},
        {"4h", null, "U+2783;"},
        {"5h", null, "U+2784;"},
        {"6h", null, "U+2785;"},
        {"7h", null, "U+2786;"},
        {"8h", null, "U+2787;"},
        {"9h", null, "U+2788;"},
        {"10h", null, "U+2789;"}
    };

    private final Gloss plugin;
    private final File folder;
    private volatile List<EmojiEntry> entries;
    private volatile EmojiReplacer replacer;
    private volatile FolderWatcher watcher;
    private int watchTaskId;

    public EmojiService(Gloss plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "emoji");
        this.entries = List.of();
        this.replacer = new EmojiReplacer(List.of());
        this.watchTaskId = -1;
    }

    public void enable() {
        if (!plugin.cfg().emoji().enabled()) {
            return;
        }
        loadEntries();
        plugin.text().setEmojiFilter(this::apply);
        plugin.text().setChatEmojiFilter(this::applyFor);
        watcher = new FolderWatcher(folder);
        watchTaskId = plugin.scheduler().ar(this::pollWatcher, plugin.cfg().hotload().watchIntervalTicks());
    }

    public void disable() {
        if (watchTaskId >= 0) {
            plugin.scheduler().car(watchTaskId);
            watchTaskId = -1;
        }
        watcher = null;
        plugin.text().setChatEmojiFilter(null);
        plugin.text().setEmojiFilter(null);
        entries = List.of();
        replacer = new EmojiReplacer(List.of());
    }

    public void reload() {
        disable();
        enable();
    }

    public List<EmojiEntry> all() {
        return entries;
    }

    public String apply(String message) {
        return replacer.apply(message);
    }

    public String applyFor(Player sender, String message) {
        if (sender == null || !plugin.cfg().emoji().emojiSpecificPermissions()) {
            return replacer.apply(message);
        }

        return replacer.apply(message, id -> sender.hasPermission(PERMISSION_PREFIX + id));
    }

    public List<String> suggestions(String prefix) {
        List<EmojiEntry> snapshot = entries;
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(snapshot.size());
        for (EmojiEntry entry : snapshot) {
            if (!entry.enabled()) {
                continue;
            }

            String token = entry.token();
            if (needle.isEmpty() || token.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(token);
            }
        }

        return out;
    }

    private void pollWatcher() {
        FolderWatcher active = watcher;
        if (active == null || !active.checkModified()) {
            return;
        }

        loadEntries();
    }

    private void loadEntries() {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            Gloss.warn("Unable to create emoji folder at " + folder.getAbsolutePath());
        }

        File[] files = listJson();
        if (files.length == 0) {
            writeDefaults();
            files = listJson();
        }

        List<EmojiEntry> loaded = new ArrayList<>(files.length);
        for (File file : files) {
            EmojiEntry entry = readEntry(file);
            if (entry != null) {
                loaded.add(entry);
            }
        }

        loaded.sort(Comparator.comparing(EmojiEntry::id));
        entries = List.copyOf(loaded);
        replacer = new EmojiReplacer(entries);
    }

    private File[] listJson() {
        File[] files = folder.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        return files == null ? new File[0] : files;
    }

    private EmojiEntry readEntry(File file) {
        String id = file.getName().substring(0, file.getName().length() - 5);
        try {
            JSONObject json = new JSONObject(IO.readAll(file));
            String trigger = json.optString("trigger", EmojiEntry.NO_TRIGGER);
            String emoji = UnicodeText.parse(json.getString("emoji"));
            boolean enabled = json.optBoolean("enabled", true);
            return new EmojiEntry(id, trigger, emoji, enabled);
        } catch (IOException | RuntimeException failure) {
            Gloss.warn("Failed to load emoji " + file.getName() + ": " + failure.getMessage());
            return null;
        }
    }

    private void writeDefaults() {
        for (String[] definition : DEFAULTS) {
            File file = new File(folder, definition[0] + ".json");
            JSONObject json = new JSONObject();
            json.put("trigger", definition[1] == null ? EmojiEntry.NO_TRIGGER : definition[1]);
            json.put("emoji", definition[2]);
            json.put("enabled", true);
            try {
                IO.writeAll(file, json.toString(4));
            } catch (IOException failure) {
                Gloss.warn("Failed to write default emoji " + file.getName() + ": " + failure.getMessage());
            }
        }
    }
}
