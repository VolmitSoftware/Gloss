package art.arcane.gloss.menu;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;

/**
 * Resolves only the {@code {{ }}} spans of a string, leaving everything else byte for byte. Command
 * text takes this path instead of the text pipeline: a command is not display text, and running it
 * through colours, emoji and legacy scoping would corrupt arguments that happen to contain
 * {@code &} or a colon.
 */
public final class MenuExpressions {
    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";

    private MenuExpressions() {
    }

    /** A span that fails to parse or resolve is left exactly as the author wrote it. */
    public static String substitute(String raw, ExprScope scope) {
        if (raw == null || raw.indexOf(OPEN) < 0 || scope == null) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int cursor = 0;
        while (true) {
            int open = raw.indexOf(OPEN, cursor);
            if (open < 0) {
                break;
            }
            int close = raw.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                break;
            }
            out.append(raw, cursor, open);
            String expression = raw.substring(open + OPEN.length(), close).trim();
            out.append(resolve(expression, scope, raw.substring(open, close + CLOSE.length())));
            cursor = close + CLOSE.length();
        }
        return out.append(raw, cursor, raw.length()).toString();
    }

    private static String resolve(String expression, ExprScope scope, String original) {
        try {
            Object value = ExprEvaluator.eval(ExprParser.parse(expression), scope);
            return value == null ? original : text(value);
        } catch (RuntimeException unresolvable) {
            return original;
        }
    }

    private static String text(Object value) {
        if (value instanceof Double number && number == Math.floor(number) && !number.isInfinite()) {
            return String.valueOf(number.longValue());
        }
        return String.valueOf(value);
    }
}
