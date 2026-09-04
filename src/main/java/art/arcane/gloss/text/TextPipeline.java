package art.arcane.gloss.text;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.bukkit.Placeholders;
import art.arcane.volmlib.util.format.ColorFormatter;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.List;
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

    private static final int ALL_FLAGS = HAS_FUNCTION | HAS_PLACEHOLDER | HAS_EMOJI_CANDIDATE | HAS_COLOR;

    private static final AtomicLong EMOJI_GENERATION = new AtomicLong();

    private static volatile long[] emojiTriggerAscii = new long[2];
    private static volatile char[] emojiTriggerExtended = new char[0];
    private static volatile List<String> conditionalEmojiTokens = List.of();

    private final Gloss plugin;
    private final Map<String, Function<Player, String>> functions;
    private final Set<String> failedFunctions;
    private final AtomicLong renderGeneration;
    private final ServerTickSampler serverTicks;
    private final TextExpressionRenderer expressions;
    private volatile UnaryOperator<String> emojiFilter;
    private volatile BiFunction<Player, String, String> viewerEmojiFilter;

    public TextPipeline(Gloss plugin) {
        this.plugin = plugin;
        this.functions = new ConcurrentHashMap<>();
        this.failedFunctions = ConcurrentHashMap.newKeySet();
        this.renderGeneration = new AtomicLong();
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
        renderGeneration.incrementAndGet();
    }

    public void reload() {
        failedFunctions.clear();
        renderGeneration.incrementAndGet();
    }

    @Override
    public String render(Player viewer, String raw) {
        return renderMarked(viewer, raw);
    }

    public ParticleText.Rendered renderParticleText(Player viewer, String raw) {
        return ParticleText.render(raw, marked -> renderMarked(viewer, marked));
    }

    private String renderMarked(Player viewer, String raw) {
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
        return TextUtils.scopeLegacyLines(applyColors(out));
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
        if (containsExpression(raw) || hasConditionalEmoji(raw)) {
            return true;
        }
        return containsPair(raw, '%') || containsPair(raw, '|');
    }

    public static boolean viewerSpecific(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (containsPair(raw, '%') || TextExpressionRenderer.dependsOnViewer(raw) || hasConditionalEmoji(raw)) {
            return true;
        }
        int open = raw.indexOf('|');
        while (open >= 0) {
            int close = raw.indexOf('|', open + 1);
            if (close < 0) {
                return false;
            }
            String name = raw.substring(open + 1, close);
            if (!name.startsWith("animation.")) {
                return true;
            }
            open = raw.indexOf('|', close + 1);
        }
        return false;
    }

    public static boolean containsExpression(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        int expression = raw.indexOf("{{");
        return expression >= 0 && raw.indexOf("}}", expression + 2) >= 0;
    }

    public static boolean requiresFastRefresh(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        int open = raw.indexOf('|');
        while (open >= 0) {
            int close = raw.indexOf('|', open + 1);
            if (close < 0) {
                break;
            }
            if (raw.startsWith("animation.", open + 1) && close > open + "|animation.".length()) {
                return true;
            }
            open = raw.indexOf('|', close + 1);
        }
        return TextExpressionRenderer.dependsOnTime(raw) || hasConditionalEmoji(raw);
    }

    public static boolean timeDependent(String raw) {
        return TextExpressionRenderer.dependsOnTime(raw) || hasConditionalEmoji(raw);
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
        int flags = hasConditionalEmoji(raw) ? HAS_FUNCTION | HAS_PLACEHOLDER : 0;
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

    public static void publishConditionalEmojiTokens(Collection<String> tokens) {
        conditionalEmojiTokens = List.copyOf(tokens);
        EMOJI_GENERATION.incrementAndGet();
    }

    private static boolean hasConditionalEmoji(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        for (String token : conditionalEmojiTokens) {
            if (raw.contains(token)) {
                return true;
            }
        }
        return false;
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

    public long renderGeneration() {
        return renderGeneration.get();
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
        BiFunction<Player, String, String> emoji = viewerEmojiFilter;
        return renderChat(activeSender, message, emoji, config.emoji().enabled(), config.chat().colorEnabled());
    }

    static String renderChat(Player sender, String message, BiFunction<Player, String, String> emoji,
                             boolean emojiEnabled, boolean colorEnabled) {
        String out = message;
        if (emoji != null && emojiEnabled && sender.hasPermission("gloss.emoji.use")) {
            out = emoji.apply(sender, out);
        }
        if (colorEnabled && sender.hasPermission("gloss.chat.color")) {
            out = ColorFormatter.translateColors(out);
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
        renderGeneration.incrementAndGet();
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
        renderGeneration.incrementAndGet();
    }

    public void setEmojiFilter(UnaryOperator<String> filter) {
        emojiFilter = filter;
        renderGeneration.incrementAndGet();
    }

    public void setViewerEmojiFilter(BiFunction<Player, String, String> filter) {
        viewerEmojiFilter = filter;
        renderGeneration.incrementAndGet();
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
                Gloss.logExceptionStack(false, failure, "Text function |%s| failed.", name);
            }
            return "";
        }
    }

    private String applyColors(String input) {
        return ColorFormatter.translateColors(input);
    }
}
