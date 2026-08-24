package art.arcane.gloss.text;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.integrate.IntegrationBridgeService;
import art.arcane.volmlib.util.bukkit.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

public final class TextExpressionRenderer {
    public static final Set<String> STANDARD_VARIABLES = Set.of(
        "time.ms", "time.seconds", "time.ticks",
        "server.online", "server.maxPlayers", "server.tps",
        "player.name", "player.ping", "player.health", "player.level");
    public static final Set<String> STANDARD_FUNCTIONS = Set.of("papi", "papiNumber", "metric");

    private static final int CACHE_LIMIT = 4096;
    private static final int SOURCE_LIMIT = 1024;

    private final Gloss plugin;
    private final RuntimeValues runtimeValues;
    private final Map<String, Expr> cache;
    private final Set<String> failed;

    TextExpressionRenderer(Gloss plugin, DoubleSupplier serverTps) {
        this(plugin, RuntimeValues.bukkit(serverTps));
    }

    TextExpressionRenderer(Gloss plugin, RuntimeValues runtimeValues) {
        this.plugin = plugin;
        this.runtimeValues = Objects.requireNonNull(runtimeValues, "runtimeValues");
        this.cache = new ConcurrentHashMap<>();
        this.failed = ConcurrentHashMap.newKeySet();
    }

    public String render(Player viewer, String input) {
        if (input == null || input.isEmpty() || input.indexOf("{{") < 0) {
            return input == null ? "" : input;
        }
        StringBuilder output = null;
        int cursor = 0;
        int open = input.indexOf("{{");
        while (open >= 0) {
            int close = input.indexOf("}}", open + 2);
            if (close < 0) {
                break;
            }
            String source = input.substring(open + 2, close).trim();
            String resolved = resolve(scope(viewer), source);
            if (resolved != null) {
                if (output == null) {
                    output = new StringBuilder(input.length() + 16);
                }
                output.append(input, cursor, open);
                output.append(resolved);
                cursor = close + 2;
            }
            open = input.indexOf("{{", close + 2);
        }
        if (output == null) {
            return input;
        }
        output.append(input, cursor, input.length());
        return output.toString();
    }

    public void clear() {
        cache.clear();
        failed.clear();
    }

    static boolean dependsOnTime(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        int open = input.indexOf("{{");
        while (open >= 0) {
            int close = input.indexOf("}}", open + 2);
            if (close < 0) {
                return false;
            }
            String source = input.substring(open + 2, close).trim();
            if (!source.isEmpty() && source.length() <= SOURCE_LIMIT) {
                try {
                    if (dependsOnTime(ExprParser.parse(source))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            open = input.indexOf("{{", close + 2);
        }
        return false;
    }

    static boolean dependsOnViewer(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        int open = input.indexOf("{{");
        while (open >= 0) {
            int close = input.indexOf("}}", open + 2);
            if (close < 0) {
                return true;
            }
            String source = input.substring(open + 2, close).trim();
            if (!source.isEmpty() && source.length() <= SOURCE_LIMIT) {
                try {
                    if (dependsOnViewer(ExprParser.parse(source))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    return true;
                }
            }
            open = input.indexOf("{{", close + 2);
        }
        return false;
    }

    private static boolean dependsOnTime(Expr expression) {
        if (expression instanceof Expr.Var variable) {
            return variable.name().equals("time.ms")
                || variable.name().equals("time.seconds")
                || variable.name().equals("time.ticks");
        }
        if (expression instanceof Expr.ListLiteral list) {
            return containsTimeDependency(list.items());
        }
        if (expression instanceof Expr.Unary unary) {
            return dependsOnTime(unary.operand());
        }
        if (expression instanceof Expr.Binary binary) {
            return dependsOnTime(binary.left()) || dependsOnTime(binary.right());
        }
        if (expression instanceof Expr.Ternary ternary) {
            return dependsOnTime(ternary.condition())
                || dependsOnTime(ternary.ifTrue())
                || dependsOnTime(ternary.ifFalse());
        }
        if (expression instanceof Expr.Call call) {
            return containsTimeDependency(call.args());
        }
        return false;
    }

    private static boolean dependsOnViewer(Expr expression) {
        if (expression instanceof Expr.Var variable) {
            return variable.name().startsWith("player.");
        }
        if (expression instanceof Expr.ListLiteral list) {
            return containsViewerDependency(list.items());
        }
        if (expression instanceof Expr.Unary unary) {
            return dependsOnViewer(unary.operand());
        }
        if (expression instanceof Expr.Binary binary) {
            return dependsOnViewer(binary.left()) || dependsOnViewer(binary.right());
        }
        if (expression instanceof Expr.Ternary ternary) {
            return dependsOnViewer(ternary.condition())
                || dependsOnViewer(ternary.ifTrue())
                || dependsOnViewer(ternary.ifFalse());
        }
        if (expression instanceof Expr.Call call) {
            return call.name().equals("papi") || call.name().equals("papiNumber")
                || containsViewerDependency(call.args());
        }
        return false;
    }

    private static boolean containsTimeDependency(List<Expr> expressions) {
        for (Expr expression : expressions) {
            if (dependsOnTime(expression)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsViewerDependency(List<Expr> expressions) {
        for (Expr expression : expressions) {
            if (dependsOnViewer(expression)) {
                return true;
            }
        }
        return false;
    }

    ExprScope scope(Player viewer) {
        return new Scope(viewer, System.currentTimeMillis());
    }

    private String resolve(ExprScope scope, String source) {
        if (source.isEmpty() || source.length() > SOURCE_LIMIT) {
            return null;
        }
        try {
            Expr expression = cached(source);
            return ExprEvaluator.string(expression, scope);
        } catch (RuntimeException failure) {
            if (failed.add(source)) {
                Gloss.logExceptionStack(false, failure, "Text expression {{ %s }} failed.", source);
            }
            return null;
        }
    }

    private Expr cached(String source) {
        Expr expression = cache.get(source);
        if (expression != null) {
            return expression;
        }
        Expr parsed = ExprParser.parse(source);
        if (cache.size() >= CACHE_LIMIT) {
            return parsed;
        }
        Expr existing = cache.putIfAbsent(source, parsed);
        return existing == null ? parsed : existing;
    }

    private final class Scope implements ExprScope {
        private final Player viewer;
        private final long nowMs;

        private Scope(Player viewer, long nowMs) {
            this.viewer = viewer;
            this.nowMs = nowMs;
        }

        @Override
        public Object variable(String name) {
            return switch (name) {
                case "time.ms" -> (double) nowMs;
                case "time.seconds" -> nowMs / 1000.0D;
                case "time.ticks" -> nowMs / 50.0D;
                case "server.online" -> (double) runtimeValues.onlinePlayers().getAsInt();
                case "server.maxPlayers" -> (double) runtimeValues.maxPlayers().getAsInt();
                case "server.tps" -> runtimeValues.serverTps().getAsDouble();
                case "player.name" -> viewer == null ? null : viewer.getName();
                case "player.ping" -> viewer == null ? null : (double) viewer.getPing();
                case "player.health" -> viewer == null ? null : viewer.getHealth();
                case "player.level" -> viewer == null ? null : (double) viewer.getLevel();
                default -> metric(name);
            };
        }

        @Override
        public Object call(String name, List<Object> args) {
            return switch (name) {
                case "papi" -> papi(args, false);
                case "papiNumber" -> papi(args, true);
                case "metric" -> metricCall(args);
                default -> ExprFunctions.call(name, args);
            };
        }

        private Object papi(List<Object> args, boolean numeric) {
            if ((args.size() != 1 && args.size() != 2) || !(args.get(0) instanceof String name)) {
                throw new IllegalArgumentException((numeric ? "papiNumber" : "papi")
                    + " expects a key and optional fallback");
            }
            Object fallback = args.size() == 2 ? args.get(1) : null;
            validateFallback(numeric, fallback);
            boolean wrapped = name.length() >= 2 && name.startsWith("%") && name.endsWith("%");
            String key = wrapped ? name.substring(1, name.length() - 1) : name;
            String token = wrapped ? name : "%" + name + "%";
            if (viewer == null) {
                Object nativeValue = nativePapiValue(key);
                if (nativeValue != null) {
                    return convertPapiValue(nativeValue, numeric, fallback);
                }
                if (fallback != null) {
                    return fallback;
                }
                if (numeric) {
                    throw new IllegalStateException("papiNumber requires a player-backed surface");
                }
                return token;
            }
            String value = Placeholders.setPlaceholders(viewer, token);
            if (!value.equals(token)) {
                return convertPapiValue(value, numeric, fallback);
            }
            Object nativeValue = nativePapiValue(key);
            if (nativeValue != null) {
                return convertPapiValue(nativeValue, numeric, fallback);
            }
            if (fallback != null) {
                return fallback;
            }
            if (!numeric) {
                return value;
            }
            return convertPapiValue(value, true, null);
        }

        private Object nativePapiValue(String key) {
            String variableName = switch (key) {
                case "player_name" -> "player.name";
                case "player_ping" -> "player.ping";
                case "player_health" -> "player.health";
                case "player_level" -> "player.level";
                case "server_online" -> "server.online";
                case "server_max_players" -> "server.maxPlayers";
                case "server_tps" -> "server.tps";
                default -> null;
            };
            return variableName == null ? null : variable(variableName);
        }

        private Object convertPapiValue(Object value, boolean numeric, Object fallback) {
            if (!numeric) {
                return ExprFunctions.call("str", List.of(value));
            }
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            try {
                return ExprFunctions.call("number", List.of(value));
            } catch (RuntimeException failure) {
                if (fallback != null) {
                    return fallback;
                }
                throw failure;
            }
        }

        private Object metricCall(List<Object> args) {
            if ((args.size() != 1 && args.size() != 2) || !(args.get(0) instanceof String key)) {
                throw new IllegalArgumentException("metric expects a key and optional numeric fallback");
            }
            Object fallback = args.size() == 2 ? args.get(1) : null;
            if (fallback != null && !(fallback instanceof Number)) {
                throw new IllegalArgumentException("metric fallback must be a number");
            }
            Double value = metric(key);
            if (value == null) {
                if (fallback != null) {
                    return ((Number) fallback).doubleValue();
                }
                throw new IllegalArgumentException("unknown metric: " + key);
            }
            return value;
        }

        private void validateFallback(boolean numeric, Object fallback) {
            if (fallback == null) {
                return;
            }
            if (numeric && !(fallback instanceof Number)) {
                throw new IllegalArgumentException("papiNumber fallback must be a number");
            }
            if (!numeric && !(fallback instanceof String)) {
                throw new IllegalArgumentException("papi fallback must be a string");
            }
        }

        private Double metric(String key) {
            if ("react.tps".equals(key)) {
                return runtimeValues.serverTps().getAsDouble();
            }
            IntegrationBridgeService service = plugin == null ? null : plugin.getIntegrationBridge();
            return service == null ? null : service.bridge().value(key, nowMs);
        }
    }

    record RuntimeValues(IntSupplier onlinePlayers, IntSupplier maxPlayers, DoubleSupplier serverTps) {
        RuntimeValues {
            Objects.requireNonNull(onlinePlayers, "onlinePlayers");
            Objects.requireNonNull(maxPlayers, "maxPlayers");
            Objects.requireNonNull(serverTps, "serverTps");
        }

        static RuntimeValues bukkit(DoubleSupplier serverTps) {
            return new RuntimeValues(() -> Bukkit.getOnlinePlayers().size(), Bukkit::getMaxPlayers, serverTps);
        }
    }
}
