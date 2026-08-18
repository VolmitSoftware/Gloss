package art.arcane.gloss.emoji;

public final class UnicodeText {
    private UnicodeText() {
    }

    public static String parse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('U') < 0) {
            return text;
        }

        int length = text.length();
        StringBuilder out = new StringBuilder(length);
        StringBuilder buffer = new StringBuilder(8);
        boolean reading = false;
        for (int i = 0; i < length; i++) {
            char current = text.charAt(i);
            if (reading) {
                if (current == ';' && buffer.length() > 0) {
                    reading = false;
                    appendCodepoint(out, buffer.toString());
                    buffer.setLength(0);
                } else {
                    buffer.append(current);
                }
                continue;
            }

            if (current == 'U' && i + 1 < length && text.charAt(i + 1) == '+') {
                i++;
                buffer.setLength(0);
                reading = true;
                continue;
            }

            out.append(current);
        }

        if (reading) {
            flushTail(out, buffer.toString());
        }

        return out.toString();
    }

    private static void appendCodepoint(StringBuilder out, String hex) {
        try {
            out.append(Character.toChars(Integer.parseInt(hex, 16)));
        } catch (IllegalArgumentException failure) {
            out.append('?');
        }
    }

    private static void flushTail(StringBuilder out, String hex) {
        String parsed = codepointOrNull(hex);
        if (parsed != null) {
            out.append(parsed);
            return;
        }
        out.append("U+").append(hex);
    }

    private static String codepointOrNull(String hex) {
        if (hex.isEmpty()) {
            return null;
        }
        try {
            return new String(Character.toChars(Integer.parseInt(hex, 16)));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }
}
