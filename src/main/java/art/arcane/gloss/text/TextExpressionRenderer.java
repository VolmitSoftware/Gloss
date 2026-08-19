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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TextExpressionRenderer {
    private static final int CACHE_LIMIT = 4096;
    private static final int SOURCE_LIMIT = 1024;

    private final Gloss plugin;
    private final Map<String, Expr> cache;
    private final Set<String> failed;

    public TextExpressionRenderer(Gloss plugin) {
        this.plugin = plugin;
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
            String resolved = resolve(viewer, source);
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

    private String resolve(Player viewer, String source) {
        if (source.isEmpty() || source.length() > SOURCE_LIMIT) {
            return null;
        }
        try {
            Expr expression = cached(source);
            return ExprEvaluator.string(expression, new Scope(viewer, System.currentTimeMillis()));
        } catch (RuntimeException failure) {
            if (failed.add(source)) {
                Gloss.warn("Text expression {{ " + source + " }} failed: " + failure.getMessage());
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
                case "server.online" -> (double) Bukkit.getOnlinePlayers().size();
                case "server.maxPlayers" -> (double) Bukkit.getMaxPlayers();
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
            if (args.size() != 1 || !(args.get(0) instanceof String name)) {
                throw new IllegalArgumentException((numeric ? "papiNumber" : "papi") + " expects one string");
            }
            String token = name.startsWith("%") && name.endsWith("%") ? name : "%" + name + "%";
            if (viewer == null) {
                if (numeric) {
                    throw new IllegalStateException("papiNumber requires a player-backed surface");
                }
                return token;
            }
            String value = Placeholders.setPlaceholders(viewer, token);
            return numeric ? ExprFunctions.call("number", List.of(value)) : value;
        }

        private Object metricCall(List<Object> args) {
            if (args.size() != 1 || !(args.get(0) instanceof String key)) {
                throw new IllegalArgumentException("metric expects one string");
            }
            Double value = metric(key);
            if (value == null) {
                throw new IllegalArgumentException("unknown metric: " + key);
            }
            return value;
        }

        private Double metric(String key) {
            IntegrationBridgeService service = plugin == null ? null : plugin.getIntegrationBridge();
            return service == null ? null : service.bridge().value(key, nowMs);
        }
    }
}
