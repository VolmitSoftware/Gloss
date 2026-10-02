package art.arcane.gloss.names;

import java.util.Locale;
import java.util.Set;

public final class NameFallback {
    private static final Set<String> JOINING_WORDS = Set.of("a", "an", "and", "in", "o", "of", "on", "the", "with");

    private NameFallback() {
    }

    public static String titleCase(String source) {
        String key = source == null ? "" : source.trim();
        int namespace = key.indexOf(':');
        if (namespace >= 0) {
            key = key.substring(namespace + 1);
        }
        String[] words = key.toLowerCase(Locale.ROOT).split("[_\\s-]+");
        StringBuilder name = new StringBuilder(key.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            boolean first = name.isEmpty();
            if (!first) {
                name.append(' ');
            }
            if (!first && JOINING_WORDS.contains(word)) {
                name.append(word);
            } else {
                name.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
            }
        }
        return name.toString();
    }
}
