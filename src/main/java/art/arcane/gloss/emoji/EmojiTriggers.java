package art.arcane.gloss.emoji;

public final class EmojiTriggers {
    private EmojiTriggers() {
    }

    public static String replace(String input, String trigger, String replacement) {
        if (trigger == null || trigger.isEmpty() || input.isEmpty()) {
            return input;
        }
        boolean leadingWord = word(trigger.codePointAt(0));
        boolean trailingWord = word(trigger.codePointBefore(trigger.length()));
        StringBuilder result = null;
        int cursor = 0;
        int match = input.indexOf(trigger);
        while (match >= 0) {
            int end = match + trigger.length();
            boolean before = leadingWord && match > 0 && word(input.codePointBefore(match));
            boolean after = trailingWord && end < input.length() && word(input.codePointAt(end));
            if (!before && !after) {
                if (result == null) {
                    result = new StringBuilder(input.length());
                }
                result.append(input, cursor, match).append(replacement);
                cursor = end;
            }
            match = input.indexOf(trigger, end);
        }
        return result == null ? input : result.append(input, cursor, input.length()).toString();
    }

    private static boolean word(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint) || codePoint == '_'
            || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
}
