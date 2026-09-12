package art.arcane.gloss.locale;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Argument binding for {@code lang(key, args...)}. The container-preview DSL and the
 * {@code ExprFunctionRegistry} function registered by the strings service both call these, so a
 * key renders identically whichever surface asked for it.
 */
public final class LangArguments {
    private static final String LANG_ARG_PREFIX = "arg";

    private LangArguments() {
    }

    /**
     * The catalog's own key when the id is known, else a synthesised key whose English default is
     * the id itself. The synthesised key only renders on a headless call; a running server rejects
     * an id no {@code LocalizationSnapshot} has seen.
     */
    public static TextKey messageKey(String key) {
        MessageKey known = GlossMessages.catalog().key(key);
        return known instanceof TextKey text ? text : TextKey.of(key, key);
    }

    /**
     * Binds positional call arguments onto the resolved key's own placeholder names: argument 1
     * fills the first <code>{name}</code> in the English template, argument 2 the second, and so on.
     *
     * <p>Arguments past the last placeholder are ignored for catalog keys so strict localization
     * does not receive unexpected names. Unknown headless-only keys retain positional names such as
     * {@code arg0}. Values are stringified with the expression language's own rule, so {@code 42.0}
     * inserts as {@code "42"}, and they are inserted as untrusted text so a container name can never
     * smuggle in colour codes.
     *
     * @param args the whole call argument list, the key at index 0
     */
    public static MessageArgs arguments(TextKey key, List<Object> args) {
        if (args.size() <= 1) {
            return MessageArgs.empty();
        }
        List<String> placeholders = orderedPlaceholders(key.english());
        boolean knownKey = GlossMessages.catalog().key(key.id()) != null;
        int suppliedArguments = args.size() - 1;
        int boundArguments = knownKey ? Math.min(suppliedArguments, placeholders.size()) : suppliedArguments;
        MessageArgs.Builder builder = MessageArgs.builder();
        for (int position = 0; position < boundArguments; position++) {
            String name = knownKey ? placeholders.get(position) : LANG_ARG_PREFIX + position;
            builder.untrusted(name, ExprFunctions.call("str", List.of(args.get(position + 1))));
        }
        return builder.build();
    }

    /**
     * Placeholder names in first-appearance order, honouring the <code>{{</code> escape VolmLib's
     * own scanner uses. {@code TextKey.placeholders()} cannot be used here: it returns a
     * {@code Set.copyOf(...)}, which has already lost the insertion order this binding depends on.
     */
    public static List<String> orderedPlaceholders(String template) {
        List<String> names = new ArrayList<>();
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                break;
            }
            if (open + 1 < template.length() && template.charAt(open + 1) == '{') {
                cursor = open + 2;
                continue;
            }
            int close = template.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }
            String name = template.substring(open + 1, close);
            if (!names.contains(name)) {
                names.add(name);
            }
            cursor = close + 1;
        }
        return names;
    }
}
