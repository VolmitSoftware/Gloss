package art.arcane.gloss.bubble;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class BubbleStyles {
    public static final String DEFAULT_STYLE_ID = "default";
    public static final String STYLE_PERMISSION_PREFIX = "gloss.bubbles.style.";

    private BubbleStyles() {
    }

    public static String resolveStyleId(String chosenId, Predicate<String> permissionTest,
                                        Map<String, BubbleStyleDoc> styles, String worldName, String primaryGroup) {
        if (chosenId != null && styles.containsKey(chosenId)
            && permissionTest.test(STYLE_PERMISSION_PREFIX + chosenId)) {
            return chosenId;
        }
        String matched = null;
        int matchedPriority = Integer.MIN_VALUE;
        for (Map.Entry<String, BubbleStyleDoc> entry : styles.entrySet()) {
            BubbleStyleDoc.Select select = entry.getValue().select();
            if (select == null || !matches(select, worldName, primaryGroup)) {
                continue;
            }
            if (matched == null || select.priority() > matchedPriority
                || (select.priority() == matchedPriority && entry.getKey().compareTo(matched) < 0)) {
                matched = entry.getKey();
                matchedPriority = select.priority();
            }
        }
        if (matched != null) {
            return matched;
        }
        return styles.containsKey(DEFAULT_STYLE_ID) ? DEFAULT_STYLE_ID : null;
    }

    public static boolean matches(BubbleStyleDoc.Select select, String worldName, String primaryGroup) {
        if (!select.worlds().isEmpty()) {
            if (worldName == null) {
                return false;
            }
            boolean worldMatched = false;
            for (String pattern : select.worlds()) {
                if (globMatches(pattern, worldName)) {
                    worldMatched = true;
                    break;
                }
            }
            if (!worldMatched) {
                return false;
            }
        }
        if (!select.groups().isEmpty()) {
            return primaryGroup != null && select.groups().contains(primaryGroup.toLowerCase(Locale.ROOT));
        }
        return true;
    }

    public static boolean globMatches(String pattern, String value) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current == '*' || current == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(current == '*' ? ".*" : ".");
                continue;
            }
            literal.append(current);
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return value.matches(regex.toString());
    }
}
