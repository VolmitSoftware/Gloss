package art.arcane.gloss.text;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.volmlib.util.bukkit.Placeholders;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class TextPipeline implements TextRenderer {
    private static final int HEX_CACHE_LIMIT = 4096;

    private final Gloss plugin;
    private final Map<String, Function<Player, String>> functions;
    private final Set<String> failedFunctions;
    private final Map<String, String> hexCache;
    private volatile UnaryOperator<String> emojiFilter;
    private volatile BiFunction<Player, String, String> chatEmojiFilter;

    public TextPipeline(Gloss plugin) {
        this.plugin = plugin;
        this.functions = new ConcurrentHashMap<>();
        this.failedFunctions = ConcurrentHashMap.newKeySet();
        this.hexCache = new ConcurrentHashMap<>();
    }

    public void enable() {
    }

    public void disable() {
        functions.clear();
        failedFunctions.clear();
        emojiFilter = null;
        chatEmojiFilter = null;
    }

    public void reload() {
        failedFunctions.clear();
    }

    @Override
    public String render(Player viewer, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        String out = raw;
        if (functionsEnabled() && out.indexOf('|') >= 0) {
            out = applyFunctions(viewer, out);
        }
        if (viewer != null && placeholdersEnabled() && out.indexOf('%') >= 0) {
            out = Placeholders.setPlaceholders(viewer, out);
        }
        UnaryOperator<String> emoji = emojiFilter;
        if (emoji != null) {
            out = emoji.apply(out);
        }
        return applyColors(out);
    }

    @Override
    public String renderStatic(String raw) {
        return render(null, raw);
    }

    @Override
    public String chat(Player sender, String message) {
        Player activeSender = Objects.requireNonNull(sender);
        if (message == null || message.isEmpty()) {
            return "";
        }

        GlossConfig config = plugin.cfg();
        String out = message;
        BiFunction<Player, String, String> emoji = chatEmojiFilter;
        if (emoji != null && config.emoji().enabled() && activeSender.hasPermission("gloss.emoji.use")) {
            out = emoji.apply(activeSender, out);
        }
        if (config.chat().colorEnabled() && activeSender.hasPermission("gloss.chat.color")) {
            out = applyColors(out);
        }
        return out;
    }

    @Override
    public void registerFunction(String name, Function<Player, String> resolver) {
        if (name == null || name.isEmpty() || resolver == null) {
            return;
        }

        functions.put(name, resolver);
        failedFunctions.remove(name);
    }

    @Override
    public void unregisterFunction(String name) {
        if (name == null) {
            return;
        }

        functions.remove(name);
        failedFunctions.remove(name);
    }

    public void setEmojiFilter(UnaryOperator<String> filter) {
        emojiFilter = filter;
    }

    public void setChatEmojiFilter(BiFunction<Player, String, String> filter) {
        chatEmojiFilter = filter;
    }

    private boolean functionsEnabled() {
        GlossConfig config = plugin == null ? null : plugin.cfg();
        return config == null || config.text().functions();
    }

    private boolean placeholdersEnabled() {
        GlossConfig config = plugin == null ? null : plugin.cfg();
        return config == null || config.text().placeholders();
    }

    private String applyFunctions(Player player, String input) {
        if (functions.isEmpty()) {
            return input;
        }

        StringBuilder out = null;
        int cursor = 0;
        int open = input.indexOf('|');
        while (open >= 0) {
            int close = input.indexOf('|', open + 1);
            if (close < 0) {
                break;
            }

            String name = input.substring(open + 1, close);
            Function<Player, String> resolver = functions.get(name);
            if (resolver == null) {
                open = close;
                continue;
            }

            if (out == null) {
                out = new StringBuilder(input.length() + 16);
            }

            out.append(input, cursor, open);
            out.append(resolve(name, resolver, player));
            cursor = close + 1;
            open = input.indexOf('|', cursor);
        }

        if (out == null) {
            return input;
        }

        out.append(input, cursor, input.length());
        return out.toString();
    }

    private String resolve(String name, Function<Player, String> resolver, Player player) {
        try {
            String value = resolver.apply(player);
            return value == null ? "" : value;
        } catch (Throwable failure) {
            if (failedFunctions.add(name)) {
                Gloss.warn("Text function |" + name + "| failed: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
            }
            return "";
        }
    }

    private String applyColors(String input) {
        String out = input;
        if (out.indexOf('[') >= 0) {
            out = translateBracketHex(out);
        }
        if (out.indexOf('&') >= 0) {
            out = ChatColor.translateAlternateColorCodes('&', out);
        }
        return out;
    }

    private String translateBracketHex(String input) {
        StringBuilder out = null;
        int cursor = 0;
        int open = input.indexOf('[');
        while (open >= 0) {
            if (open + 7 >= input.length() || input.charAt(open + 7) != ']' || !isHex(input, open + 1)) {
                open = input.indexOf('[', open + 1);
                continue;
            }

            if (out == null) {
                out = new StringBuilder(input.length() + 24);
            }

            out.append(input, cursor, open);
            out.append(translateHex(input.substring(open + 1, open + 7).toLowerCase(Locale.ROOT)));
            cursor = open + 8;
            open = input.indexOf('[', cursor);
        }

        if (out == null) {
            return input;
        }

        out.append(input, cursor, input.length());
        return out.toString();
    }

    private String translateHex(String hex) {
        String cached = hexCache.get(hex);
        if (cached != null) {
            return cached;
        }
        if (hexCache.size() >= HEX_CACHE_LIMIT) {
            return ChatColor.of("#" + hex).toString();
        }
        return hexCache.computeIfAbsent(hex, key -> ChatColor.of("#" + key).toString());
    }

    private boolean isHex(String input, int offset) {
        for (int i = offset; i < offset + 6; i++) {
            char value = input.charAt(i);
            boolean hex = (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
