package art.arcane.gloss.text;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.bukkit.Placeholders;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class TextPipeline implements TextRenderer {
    public static final int HAS_FUNCTION = 1;
    public static final int HAS_PLACEHOLDER = 1 << 1;
    public static final int HAS_EMOJI_CANDIDATE = 1 << 2;
    public static final int HAS_COLOR = 1 << 3;

    private static final int HEX_CACHE_LIMIT = 4096;
    private static final int ALL_FLAGS = HAS_FUNCTION | HAS_PLACEHOLDER | HAS_EMOJI_CANDIDATE | HAS_COLOR;

    private static final AtomicLong EMOJI_GENERATION = new AtomicLong();

    private static volatile long[] emojiTriggerAscii = new long[2];
    private static volatile char[] emojiTriggerExtended = new char[0];

    private final Gloss plugin;
    private final Map<String, Function<Player, String>> functions;
    private final Set<String> failedFunctions;
    private final Map<String, String> hexCache;
    private final ServerTickSampler serverTicks;
    private final TextExpressionRenderer expressions;
    private volatile UnaryOperator<String> emojiFilter;
    private volatile BiFunction<Player, String, String> viewerEmojiFilter;

    public TextPipeline(Gloss plugin) {
        this.plugin = plugin;
        this.functions = new ConcurrentHashMap<>();
        this.failedFunctions = ConcurrentHashMap.newKeySet();
        this.hexCache = new ConcurrentHashMap<>();
        this.serverTicks = new ServerTickSampler(plugin);
        this.expressions = new TextExpressionRenderer(plugin, serverTicks::tps);
    }

    public void enable() {
        serverTicks.enable();
    }

    public void disable() {
        functions.clear();
        failedFunctions.clear();
        emojiFilter = null;
        viewerEmojiFilter = null;
        serverTicks.disable();
        expressions.clear();
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
        if (functionsEnabled() && out.indexOf("{{") >= 0) {
            out = expressions.render(viewer, out);
        }
        if (viewer != null && placeholdersEnabled() && out.indexOf('%') >= 0) {
            out = Placeholders.setPlaceholders(viewer, out);
        }
        out = applyEmoji(viewer, out);
        return applyColors(out);
    }

    @Override
    public String renderStatic(String raw) {
        return render(null, raw);
    }

    public String renderMenuText(Player viewer, String raw) {
        return render(viewer, raw);
    }

    public String applyEmoji(Player viewer, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        BiFunction<Player, String, String> viewerEmoji = viewerEmojiFilter;
        if (viewer != null && viewerEmoji != null) {
            return viewerEmoji.apply(viewer, raw);
        }
        UnaryOperator<String> emoji = emojiFilter;
        return emoji == null ? raw : emoji.apply(raw);
    }

    public static String menuText(Player viewer, String raw) {
        TextPipeline pipeline = active();
        return pipeline == null ? raw : pipeline.renderMenuText(viewer, raw);
    }

    public ExprScope expressionScope(Player viewer) {
        return expressions.scope(viewer);
    }

    public static boolean viewerDependent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        int expression = raw.indexOf("{{");
        if (expression >= 0 && raw.indexOf("}}", expression + 2) >= 0) {
            return true;
        }
        return containsPair(raw, '%') || containsPair(raw, '|');
    }

    private static boolean containsPair(String raw, char marker) {
        int open = raw.indexOf(marker);
        return open >= 0 && raw.indexOf(marker, open + 1) > open + 1;
    }

    public static String emojiText(String raw) {
        TextPipeline pipeline = active();
        return pipeline == null ? raw : pipeline.applyEmoji(null, raw);
    }

    private static TextPipeline active() {
        Gloss plugin = Gloss.instance;
        return plugin == null ? null : plugin.text();
    }

    public static int classify(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }

        long[] triggerAscii = emojiTriggerAscii;
        char[] triggerExtended = emojiTriggerExtended;
        int flags = 0;
        int colons = 0;
        int length = raw.length();
        for (int i = 0; i < length; i++) {
            char value = raw.charAt(i);
            if (value == '|') {
                flags |= HAS_FUNCTION;
            } else if (value == '{' && i + 1 < length && raw.charAt(i + 1) == '{') {
                flags |= HAS_FUNCTION;
            } else if (value == '%') {
                flags |= HAS_PLACEHOLDER;
            } else if (value == '&' || value == '§' || value == '[') {
                flags |= HAS_COLOR;
            } else if (value == ':') {
                colons++;
                if (colons >= 2) {
                    flags |= HAS_EMOJI_CANDIDATE;
                }
            }
            if ((flags & HAS_EMOJI_CANDIDATE) == 0 && isEmojiTrigger(value, triggerAscii, triggerExtended)) {
                flags |= HAS_EMOJI_CANDIDATE;
            }
            if (flags == ALL_FLAGS) {
                return flags;
            }
        }
        return flags;
    }

    public static void publishEmojiTriggers(Collection<String> triggers) {
        long[] ascii = new long[2];
        StringBuilder extended = new StringBuilder();
        if (triggers != null) {
            for (String trigger : triggers) {
                if (trigger == null || trigger.isEmpty()) {
                    continue;
                }
                char first = trigger.charAt(0);
                if (first < 128) {
                    ascii[first >>> 6] |= 1L << (first & 63);
                } else if (extended.indexOf(String.valueOf(first)) < 0) {
                    extended.append(first);
                }
            }
        }
        emojiTriggerAscii = ascii;
        emojiTriggerExtended = extended.toString().toCharArray();
        EMOJI_GENERATION.incrementAndGet();
    }

    /**
     * Monotonic generation for the published emoji state. Bumped on every emoji registry
     * rebuild (and on emoji disable), so render memos keyed on it invalidate when either
     * the trigger set or the replacement table changes.
     */
    public static long emojiGeneration() {
        return EMOJI_GENERATION.get();
    }

    private static boolean isEmojiTrigger(char value, long[] ascii, char[] extended) {
        if (value < 128) {
            return (ascii[value >>> 6] & (1L << (value & 63))) != 0L;
        }
        for (char candidate : extended) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String chat(Player sender, String message) {
        Player activeSender = Objects.requireNonNull(sender);
        if (message == null || message.isEmpty()) {
            return "";
        }

        GlossConfig config = plugin.cfg();
        String out = message;
        BiFunction<Player, String, String> emoji = viewerEmojiFilter;
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

    public boolean hasFunction(String name) {
        return name != null && functions.containsKey(name);
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

    public void setViewerEmojiFilter(BiFunction<Player, String, String> filter) {
        viewerEmojiFilter = filter;
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
