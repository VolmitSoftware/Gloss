package art.arcane.gloss.bubble;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BubbleTextBlock {
    private BubbleTextBlock() {
    }

    public static List<String> wrap(String renderedPrefix, String formattedMessage, int wrapChars) {
        if (formattedMessage == null) {
            return List.of();
        }
        String prefix = renderedPrefix == null ? "" : renderedPrefix;
        List<Token> tokens = tokenize(prefix + formattedMessage);
        Writer writer = new Writer(Math.max(1, wrapChars));
        List<Token> word = new ArrayList<>();
        int wordWidth = 0;
        for (Token token : tokens) {
            if (token.format()) {
                word.add(token);
                continue;
            }
            if (!token.whitespace()) {
                word.add(token);
                wordWidth++;
                continue;
            }
            writer.appendWord(word, wordWidth);
            word.clear();
            wordWidth = 0;
            if (token.newline()) {
                writer.finishLine(true);
            } else {
                writer.space();
            }
        }
        writer.appendWord(word, wordWidth);
        writer.finishLine();
        return writer.lines();
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>(input.length());
        int index = 0;
        while (index < input.length()) {
            if (isRgb(input, index)) {
                tokens.add(Token.format(input.substring(index, index + 14), 'x'));
                index += 14;
                continue;
            }
            char value = input.charAt(index);
            if (value == '§' && index + 1 < input.length() && isLegacyCode(input.charAt(index + 1))) {
                char code = Character.toLowerCase(input.charAt(index + 1));
                tokens.add(Token.format(input.substring(index, index + 2), code));
                index += 2;
                continue;
            }
            if (input.charAt(index) == '\r' && index + 1 < input.length() && input.charAt(index + 1) == '\n') {
                tokens.add(Token.glyph("\r\n", true, true));
                index += 2;
                continue;
            }
            int codePoint = input.codePointAt(index);
            String raw = new String(Character.toChars(codePoint));
            boolean newline = codePoint == '\n' || codePoint == '\r' || codePoint == 0x2028 || codePoint == 0x2029;
            boolean whitespace = newline || codePoint == ' ' || codePoint == '\t';
            tokens.add(Token.glyph(raw, whitespace, newline));
            index += Character.charCount(codePoint);
        }
        return tokens;
    }

    private static boolean isRgb(String input, int index) {
        if (index + 14 > input.length() || input.charAt(index) != '§'
            || Character.toLowerCase(input.charAt(index + 1)) != 'x') {
            return false;
        }
        for (int offset = 2; offset < 14; offset += 2) {
            if (input.charAt(index + offset) != '§' || !isHex(input.charAt(index + offset + 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLegacyCode(char value) {
        char normalized = Character.toLowerCase(value);
        return isHex(normalized) || normalized == 'k' || normalized == 'l' || normalized == 'm'
            || normalized == 'n' || normalized == 'o' || normalized == 'r';
    }

    private static boolean isHex(char value) {
        char normalized = Character.toLowerCase(value);
        return normalized >= '0' && normalized <= '9' || normalized >= 'a' && normalized <= 'f';
    }

    private record Token(String raw, boolean format, char formatCode, boolean whitespace, boolean newline) {
        private static Token format(String raw, char code) {
            return new Token(raw, true, code, false, false);
        }

        private static Token glyph(String raw, boolean whitespace, boolean newline) {
            return new Token(raw, false, '\0', whitespace, newline);
        }
    }

    private static final class Writer {
        private final int wrapChars;
        private final List<String> lines;
        private final FormatState formatting;
        private StringBuilder line;
        private int width;
        private boolean pendingSpace;

        private Writer(int wrapChars) {
            this.wrapChars = wrapChars;
            this.lines = new ArrayList<>();
            this.formatting = new FormatState();
            this.line = new StringBuilder();
        }

        private void appendWord(List<Token> word, int wordWidth) {
            if (word.isEmpty()) {
                return;
            }
            if (wordWidth == 0) {
                for (Token token : word) {
                    append(token);
                }
                return;
            }
            int spacing = pendingSpace && width > 0 ? 1 : 0;
            if (width > 0 && width + spacing + wordWidth > wrapChars) {
                finishLine(false);
                spacing = 0;
            }
            if (spacing > 0) {
                append(Token.glyph(" ", true, false));
            }
            pendingSpace = false;
            for (Token token : word) {
                if (!token.format() && width >= wrapChars) {
                    finishLine(false);
                }
                append(token);
            }
        }

        private void append(Token token) {
            line.append(token.raw());
            if (token.format()) {
                formatting.apply(token.raw(), token.formatCode());
            } else {
                width++;
            }
        }

        private void space() {
            if (width > 0) {
                pendingSpace = true;
            }
        }

        private void finishLine() {
            finishLine(false);
            while (!lines.isEmpty() && lines.getLast().isEmpty()) {
                lines.removeLast();
            }
        }

        private void finishLine(boolean preserveBlank) {
            if (width > 0 || preserveBlank) {
                lines.add(width > 0 ? line.toString() : "");
            }
            line = new StringBuilder(formatting.codes());
            width = 0;
            pendingSpace = false;
        }

        private List<String> lines() {
            return List.copyOf(lines);
        }
    }

    private static final class FormatState {
        private final Map<Character, String> decorations = new LinkedHashMap<>();
        private String color;

        private void apply(String raw, char code) {
            if (code == 'x' || isHex(code)) {
                color = raw;
                decorations.clear();
                return;
            }
            if (code == 'r') {
                color = null;
                decorations.clear();
                return;
            }
            decorations.put(code, raw);
        }

        private String codes() {
            StringBuilder builder = new StringBuilder();
            if (color != null) {
                builder.append(color);
            }
            for (String decoration : decorations.values()) {
                builder.append(decoration);
            }
            return builder.toString();
        }
    }
}
