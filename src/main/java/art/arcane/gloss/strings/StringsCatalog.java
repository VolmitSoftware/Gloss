package art.arcane.gloss.strings;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.locale.LangArguments;
import art.arcane.volmlib.util.format.ColorFormatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The immutable per-locale view of every loaded {@code strings/} document. Resolution walks the
 * viewer's locale, that document's declared fallback chain, then {@code en_US}; a key no locale
 * carries returns null so the caller can fall through to the plugin's own message catalog.
 */
public final class StringsCatalog {
    public static final String ROOT_LOCALE = "en_US";
    public static final StringsCatalog EMPTY = new StringsCatalog(Map.of(), Map.of());

    private static final int MAX_FALLBACK_DEPTH = 8;
    private static final Pattern COLOUR_CODE = Pattern.compile("(?i)[&\u00a7][0-9A-FK-ORX]");

    private final Map<String, Map<String, String>> entries;
    private final Map<String, String> fallbacks;

    private StringsCatalog(Map<String, Map<String, String>> entries, Map<String, String> fallbacks) {
        this.entries = entries;
        this.fallbacks = fallbacks;
    }

    public static StringsCatalog of(Collection<StringsDoc> documents) {
        Map<String, Map<String, String>> entries = new HashMap<>(documents.size());
        Map<String, String> fallbacks = new HashMap<>(documents.size());
        for (StringsDoc document : documents) {
            entries.put(document.locale(), document.entries());
            if (!document.fallback().isEmpty()) {
                fallbacks.put(document.locale(), document.fallback());
            }
        }
        return new StringsCatalog(Map.copyOf(entries), Map.copyOf(fallbacks));
    }

    public Set<String> locales() {
        return entries.keySet();
    }

    public String fallbackOf(String locale) {
        return fallbacks.getOrDefault(locale, "");
    }

    public Map<String, String> entries(String locale) {
        return entries.getOrDefault(locale, Map.of());
    }

    /** @return the raw template, or null when neither the locale, its fallbacks nor {@code en_US} carry the key */
    public String resolve(String locale, String key) {
        if (key == null) {
            return null;
        }
        Set<String> seen = new HashSet<>(MAX_FALLBACK_DEPTH);
        String current = locale;
        for (int depth = 0; current != null && depth < MAX_FALLBACK_DEPTH && seen.add(current); depth++) {
            String value = entries(current).get(key);
            if (value != null) {
                return value;
            }
            current = fallbacks.get(current);
        }
        return seen.contains(ROOT_LOCALE) ? null : entries(ROOT_LOCALE).get(key);
    }

    /** Keys {@code en_US} declares that this locale does not, sorted. */
    public List<String> missing(String locale) {
        List<String> missing = new ArrayList<>();
        Map<String, String> present = entries(locale);
        for (String key : entries(ROOT_LOCALE).keySet()) {
            if (!present.containsKey(key)) {
                missing.add(key);
            }
        }
        missing.sort(String::compareTo);
        return List.copyOf(missing);
    }

    /**
     * Binds positional call arguments onto the template's own {@code {placeholder}} names, in
     * first-appearance order. Values are stringified with the expression language's rule and
     * inserted as untrusted text, so an argument can never smuggle colour codes into content.
     *
     * @param args the whole {@code lang} call argument list, the key at index 0
     */
    public static String bind(String template, List<Object> args) {
        if (args.size() <= 1) {
            return template;
        }
        List<String> placeholders = LangArguments.orderedPlaceholders(template);
        if (placeholders.isEmpty()) {
            return template;
        }
        int bound = Math.min(args.size() - 1, placeholders.size());
        Map<String, String> values = new HashMap<>(bound);
        for (int position = 0; position < bound; position++) {
            values.put(placeholders.get(position),
                untrusted(ExprFunctions.call("str", List.of(args.get(position + 1)))));
        }
        return substitute(template, values);
    }

    private static String substitute(String template, Map<String, String> values) {
        StringBuilder output = new StringBuilder(template.length() + 16);
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                break;
            }
            int close = template.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }
            String replacement = values.get(template.substring(open + 1, close));
            if (replacement == null) {
                output.append(template, cursor, close + 1);
                cursor = close + 1;
                continue;
            }
            output.append(template, cursor, open).append(replacement);
            cursor = close + 1;
        }
        return output.append(template, cursor, template.length()).toString();
    }

    /**
     * Content templates run through the text pipeline, which translates {@code &} codes, so an
     * argument is neutralised for both marker characters before it is spliced in.
     */
    private static String untrusted(Object value) {
        String stripped = ColorFormatter.stripColor(String.valueOf(value));
        return stripped == null ? "" : COLOUR_CODE.matcher(stripped).replaceAll("").replace("\u00a7", "");
    }
}
