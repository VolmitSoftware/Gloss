package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProxyText {
    private static final Pattern TOKENS = Pattern.compile("\\$(player|server|ping|online|max)(?![A-Za-z0-9_])");
    private static final Pattern HEX = Pattern.compile("\\[([0-9a-fA-F]{6})]");
    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.builder()
        .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private final ProxyServer proxy;
    private final Map<String, Expr> expressions = new ConcurrentHashMap<>();

    public ProxyText(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public static Expr parseExpression(String source) {
        if (source == null || source.length() > 1024) {
            throw new IllegalArgumentException("Expression exceeds 1024 characters or is null");
        }
        Expr expression = ExprParser.parse(source);
        validateExpression(expression);
        return expression;
    }

    public static void validateTemplate(String template) {
        if (template == null) {
            return;
        }
        int open = template.indexOf("{{");
        while (open >= 0) {
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed text expression");
            }
            parseExpression(template.substring(open + 2, close).trim());
            open = template.indexOf("{{", close + 2);
        }
    }

    private static void validateExpression(Expr expression) {
        switch (expression) {
            case Expr.Var variable -> {
                String name = variable.name();
                int dot = name.indexOf('.');
                boolean player = dot > 0 && Set.of("viewer", "subject", "player").contains(name.substring(0, dot))
                    && Set.of("present", "name", "uuid", "ping", "server").contains(name.substring(dot + 1));
                if (!player && !Set.of("server.online", "server.maxPlayers", "time.ms", "time.seconds", "time.ticks").contains(name)) {
                    throw new IllegalArgumentException("Unknown proxy variable: " + name);
                }
            }
            case Expr.Call call -> {
                if (!ExprFunctions.isBuiltIn(call.name())
                    && !Set.of("hasPermission", "oneOf", "contains", "startsWith", "endsWith").contains(call.name())) {
                    throw new IllegalArgumentException("Unknown proxy function: " + call.name());
                }
                call.args().forEach(ProxyText::validateExpression);
            }
            case Expr.Unary unary -> validateExpression(unary.operand());
            case Expr.Binary binary -> {
                validateExpression(binary.left());
                validateExpression(binary.right());
            }
            case Expr.Ternary ternary -> {
                validateExpression(ternary.condition());
                validateExpression(ternary.ifTrue());
                validateExpression(ternary.ifFalse());
            }
            case Expr.ListLiteral list -> list.items().forEach(ProxyText::validateExpression);
            default -> { }
        }
    }

    public ExpressionScope scope(Player viewer, Player subject) {
        return new Scope(viewer, subject == null ? viewer : subject, System.currentTimeMillis());
    }

    public Component render(String input, ExpressionScope scope) {
        String expanded = HEX.matcher(plain(input, scope)).replaceAll("&#$1");
        return COLORS.deserialize(expanded.replace('§', '&'));
    }

    public String plain(String input, ExpressionScope scope) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(input.length());
        int cursor = 0;
        int open = input.indexOf("{{");
        while (open >= 0) {
            int close = input.indexOf("}}", open + 2);
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed text expression");
            }
            String source = input.substring(open + 2, close).trim();
            if (source.length() > 1024) {
                throw new IllegalArgumentException("Text expression exceeds 1024 characters");
            }
            Expr expression = expressions.get(source);
            if (expression == null) {
                expression = parseExpression(source);
                if (expressions.size() < 4096) {
                    expressions.putIfAbsent(source, expression);
                }
            }
            result.append(input, cursor, open).append(ExprEvaluator.string(expression, scope));
            cursor = close + 2;
            open = input.indexOf("{{", cursor);
        }
        result.append(input, cursor, input.length());
        Matcher tokens = TOKENS.matcher(result);
        return tokens.replaceAll(match -> Matcher.quoteReplacement(token(scope.variable(switch (match.group(1)) {
            case "player" -> "subject.name";
            case "server" -> "subject.server";
            case "ping" -> "subject.ping";
            case "max" -> "server.maxPlayers";
            default -> "server.online";
        }))));
    }

    public boolean test(Expr expression, ExpressionScope scope) {
        return ExprEvaluator.bool(expression, scope);
    }

    public <T> T select(ProxyDocuments.Surface<T> surface, ExpressionScope scope) {
        if (!surface.enabled() || !test(surface.show(), scope)) {
            return null;
        }
        return variant(surface.presentation(), surface.variants(), scope);
    }

    public ProxyDocuments.BoardPresentation select(ProxyDocuments.Board board, ExpressionScope scope) {
        if (!test(board.show(), scope) || !test(board.when(), scope)) {
            return null;
        }
        return variant(board.presentation(), board.variants(), scope);
    }

    private <T> T variant(T fallback, List<ProxyDocuments.Variant<T>> variants, ExpressionScope scope) {
        for (ProxyDocuments.Variant<T> variant : variants) {
            if (test(variant.when(), scope)) {
                return variant.presentation();
            }
        }
        return fallback;
    }

    private final class Scope implements ExpressionScope {
        private final Player viewer;
        private final Player subject;
        private final long now;

        private Scope(Player viewer, Player subject, long now) {
            this.viewer = viewer;
            this.subject = subject;
            this.now = now;
        }

        @Override
        public Object variable(String name) {
            if (name.startsWith("viewer.")) {
                return player(viewer, name.substring(7));
            }
            if (name.startsWith("subject.") || name.startsWith("player.")) {
                return player(subject, name.substring(name.indexOf('.') + 1));
            }
            return switch (name) {
                case "server.online" -> (double) proxy.getPlayerCount();
                case "server.maxPlayers" -> (double) proxy.getConfiguration().getShowMaxPlayers();
                case "time.ms" -> (double) now;
                case "time.seconds" -> now / 1000.0;
                case "time.ticks" -> now / 50.0;
                default -> null;
            };
        }

        @Override
        public Object call(String name, List<Object> arguments) {
            return switch (name) {
                case "hasPermission" -> permission(arguments);
                case "oneOf" -> arguments.size() == 2 && arguments.get(1) instanceof List<?> choices
                    && choices.contains(arguments.getFirst());
                case "contains" -> stringArgument(arguments, 0).contains(stringArgument(arguments, 1));
                case "startsWith" -> stringArgument(arguments, 0).startsWith(stringArgument(arguments, 1));
                case "endsWith" -> stringArgument(arguments, 0).endsWith(stringArgument(arguments, 1));
                default -> ExprFunctions.call(name, arguments);
            };
        }

        private boolean permission(List<Object> arguments) {
            if (arguments.size() != 1 && arguments.size() != 2) {
                throw new IllegalArgumentException("hasPermission requires a permission or role and permission");
            }
            Player player = subject;
            if (arguments.size() == 2) {
                String role = stringArgument(arguments, 0);
                player = switch (role) {
                    case "viewer" -> viewer;
                    case "subject", "player" -> subject;
                    default -> throw new IllegalArgumentException("Unknown permission role: " + role);
                };
            }
            return player != null && player.hasPermission(stringArgument(arguments, arguments.size() - 1));
        }

        private Object player(Player player, String field) {
            return switch (field) {
                case "present" -> player != null;
                case "name" -> player == null ? "" : player.getUsername();
                case "uuid" -> player == null ? "" : player.getUniqueId().toString();
                case "ping" -> player == null ? 0.0 : (double) player.getPing();
                case "server" -> player == null ? "" : player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName()).orElse("");
                default -> null;
            };
        }
    }

    private static String token(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return numeric == Math.rint(numeric) ? Long.toString(number.longValue()) : Double.toString(numeric);
        }
        return value == null ? "" : value.toString();
    }

    private static String stringArgument(List<Object> arguments, int index) {
        if (index >= arguments.size() || !(arguments.get(index) instanceof String value)) {
            throw new IllegalArgumentException("Expected string argument " + (index + 1));
        }
        return value;
    }
}
