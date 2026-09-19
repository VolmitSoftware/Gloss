package art.arcane.gloss.menu.action;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;

/**
 * The amount a shop action moves. Amounts are expressions because a prompt's quantity input feeds
 * them; a broken one resolves to one rather than zero, so a misconfigured shop trades wrongly
 * instead of silently trading nothing.
 */
public final class ShopAmounts {
    public static final int MAX_ITEMS = 2304;

    private ShopAmounts() {
    }

    public static int resolve(String amount, ExprScope scope) {
        if (amount == null || amount.isBlank()) {
            return 1;
        }
        try {
            Object value = ExprEvaluator.eval(ExprParser.parse(amount.trim()), scope);
            if (value instanceof Number number) {
                return Math.clamp((int) Math.round(number.doubleValue()), 1, MAX_ITEMS);
            }
            return 1;
        } catch (RuntimeException unresolvable) {
            return 1;
        }
    }

    public static double resolveMoney(String amount, ExprScope scope) {
        if (amount == null || amount.isBlank()) {
            return 0D;
        }
        try {
            Object value = ExprEvaluator.eval(ExprParser.parse(amount.trim()), scope);
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                return Math.max(0D, number.doubleValue());
            }
            return 0D;
        } catch (RuntimeException unresolvable) {
            return 0D;
        }
    }
}
