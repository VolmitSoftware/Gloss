package art.arcane.gloss.leaderboard;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;
import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * The {@code leaderboard.*} variables, readable from every {@code {{ }}}, {@code show} and
 * {@code when} expression: {@code leaderboard.<id>.<rank>.<field>}, {@code leaderboard.<id>.size},
 * {@code leaderboard.<id>.resetAt} and the viewer's own row under {@code me}.
 */
public final class LeaderboardNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "leaderboard";

    private static final String ME = "me";

    private final Function<String, LeaderboardView> views;

    public LeaderboardNamespace(Function<String, LeaderboardView> views) {
        this.views = views;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        int firstDot = suffix.indexOf('.');
        if (firstDot <= 0) {
            return null;
        }
        LeaderboardView view = views.apply(suffix.substring(0, firstDot));
        if (view == null) {
            return null;
        }
        String rest = suffix.substring(firstDot + 1);
        int secondDot = rest.indexOf('.');
        if (secondDot < 0) {
            return board(view, rest);
        }
        String selector = rest.substring(0, secondDot);
        String field = rest.substring(secondDot + 1);
        return selector.equals(ME) ? own(view, context, field) : ranked(view, selector, field);
    }

    private static Object board(LeaderboardView view, String field) {
        return switch (field) {
            case "size" -> (double) view.size();
            case "resetAt" -> view.resetAt() / 1000.0D;
            default -> null;
        };
    }

    private static Object ranked(LeaderboardView view, String selector, String field) {
        int rank = rank(selector);
        LeaderboardView.Row row = rank == 0 ? null : view.row(rank);
        if (row == null) {
            return null;
        }
        return switch (field) {
            case "name" -> row.name();
            case "value" -> row.value();
            case "formatted" -> row.formatted();
            case "uuid" -> row.uuid().toString();
            case "rank" -> (double) rank;
            default -> null;
        };
    }

    private static Object own(LeaderboardView view, ExprVariableContext context, String field) {
        Player viewer = context.viewer();
        if (viewer == null) {
            return null;
        }
        LeaderboardView.Row row = view.row(viewer.getUniqueId());
        return switch (field) {
            case "rank" -> (double) view.rankOf(viewer.getUniqueId());
            case "value" -> row == null ? 0.0D : row.value();
            case "formatted" -> row == null ? "" : row.formatted();
            case "name" -> row == null ? "" : row.name();
            case "uuid" -> viewer.getUniqueId().toString();
            default -> null;
        };
    }

    private static int rank(String selector) {
        for (int index = 0; index < selector.length(); index++) {
            if (!Character.isDigit(selector.charAt(index))) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(selector);
        } catch (NumberFormatException notARank) {
            return 0;
        }
    }
}
