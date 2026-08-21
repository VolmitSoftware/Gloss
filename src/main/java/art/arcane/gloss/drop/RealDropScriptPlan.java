package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

final class RealDropScriptPlan {
    static final int MAX_SOURCE_LENGTH = 512;
    static final double MAX_OFFSET_BLOCKS = 16.0D;
    static final double MAX_SCALE_FACTOR = 16.0D;

    static final Set<String> ENVIRONMENT_VARIABLES = Set.of("height", "blockLight", "skyLight");
    static final Set<String> VARIABLES = Set.of(
        "t", "age", "index", "count", "amount",
        "onGround", "settled", "inWater", "inLava", "bounces",
        "velocityX", "velocityY", "velocityZ", "speed",
        "height", "blockLight", "skyLight",
        "random", "material", "isBlock", "isFlat", "isThin", "pi");
    static final Set<String> FUNCTIONS = Set.of("materialIs", "materialMatches");

    private static final int MAX_PATTERN_CACHE = 256;
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final List<ScriptVariable> variables;
    private final Expr offsetX;
    private final Expr offsetY;
    private final Expr offsetZ;
    private final Expr rotationX;
    private final Expr rotationY;
    private final Expr rotationZ;
    private final Expr scaleX;
    private final Expr scaleY;
    private final Expr scaleZ;
    private final Expr glow;
    private final Expr visible;
    private final boolean environmentRequired;
    private final AtomicBoolean runtimeFailureWarned;

    private RealDropScriptPlan(GlossConfig.RealDrops.Script script) {
        Set<String> declared = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        List<ScriptVariable> compiled = new ArrayList<>(script.vars().size());
        for (GlossConfig.RealDrops.ScriptVar variable : script.vars()) {
            String name = variable.name();
            requireVariableName(name, declared);
            compiled.add(new ScriptVariable(name,
                compile("script.vars." + name, variable.expression(), declared, referenced)));
            declared.add(name);
        }
        variables = List.copyOf(compiled);
        offsetX = compile("script.offset.x", script.offset().x(), declared, referenced);
        offsetY = compile("script.offset.y", script.offset().y(), declared, referenced);
        offsetZ = compile("script.offset.z", script.offset().z(), declared, referenced);
        rotationX = compile("script.rotation.x", script.rotation().x(), declared, referenced);
        rotationY = compile("script.rotation.y", script.rotation().y(), declared, referenced);
        rotationZ = compile("script.rotation.z", script.rotation().z(), declared, referenced);
        scaleX = compile("script.scale.x", script.scale().x(), declared, referenced);
        scaleY = compile("script.scale.y", script.scale().y(), declared, referenced);
        scaleZ = compile("script.scale.z", script.scale().z(), declared, referenced);
        glow = script.glow().isBlank() ? null : compile("script.glow", script.glow(), declared, referenced);
        visible = compile("script.visible", script.visible(), declared, referenced);
        environmentRequired = !Collections.disjoint(referenced, ENVIRONMENT_VARIABLES);
        runtimeFailureWarned = new AtomicBoolean();
        validateSamples();
    }

    static RealDropScriptPlan compile(GlossConfig.RealDrops.Script script) {
        return new RealDropScriptPlan(script);
    }

    static void validate(GlossConfig.RealDrops.Script script) {
        new RealDropScriptPlan(script);
    }

    boolean environmentRequired() {
        return environmentRequired;
    }

    RealDropScriptSample sample(RealDropScriptContext context) {
        ScriptScope scope = new ScriptScope(context, variables.size());
        for (ScriptVariable variable : variables) {
            scope.define(variable.name(), value(variable, scope));
        }
        return new RealDropScriptSample(
            evaluate("script.offset.x", offsetX, scope, 0.0D, -MAX_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS),
            evaluate("script.offset.y", offsetY, scope, 0.0D, -MAX_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS),
            evaluate("script.offset.z", offsetZ, scope, 0.0D, -MAX_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS),
            evaluate("script.rotation.x", rotationX, scope, 0.0D, -3600.0D, 3600.0D),
            evaluate("script.rotation.y", rotationY, scope, 0.0D, -3600.0D, 3600.0D),
            evaluate("script.rotation.z", rotationZ, scope, 0.0D, -3600.0D, 3600.0D),
            evaluate("script.scale.x", scaleX, scope, 1.0D, 0.0D, MAX_SCALE_FACTOR),
            evaluate("script.scale.y", scaleY, scope, 1.0D, 0.0D, MAX_SCALE_FACTOR),
            evaluate("script.scale.z", scaleZ, scope, 1.0D, 0.0D, MAX_SCALE_FACTOR),
            glowColor(scope),
            flag("script.visible", visible, scope));
    }

    private double value(ScriptVariable variable, ScriptScope scope) {
        try {
            double result = ExprEvaluator.number(variable.expression(), scope);
            return Double.isFinite(result) ? result : 0.0D;
        } catch (RuntimeException failure) {
            warnRuntime("script.vars." + variable.name(), failure.getMessage());
            return 0.0D;
        }
    }

    private double evaluate(String path, Expr expression, ScriptScope scope, double fallback,
                            double minimum, double maximum) {
        try {
            double result = ExprEvaluator.number(expression, scope);
            if (!Double.isFinite(result)) {
                warnRuntime(path, "result was not finite");
                return fallback;
            }
            return Math.max(minimum, Math.min(maximum, result));
        } catch (RuntimeException failure) {
            warnRuntime(path, failure.getMessage());
            return fallback;
        }
    }

    private boolean flag(String path, Expr expression, ScriptScope scope) {
        try {
            return ExprEvaluator.bool(expression, scope);
        } catch (RuntimeException failure) {
            warnRuntime(path, failure.getMessage());
            return true;
        }
    }

    private int glowColor(ScriptScope scope) {
        if (glow == null) {
            return 0;
        }
        try {
            return color(ExprEvaluator.eval(glow, scope));
        } catch (RuntimeException failure) {
            warnRuntime("script.glow", failure.getMessage());
            return 0;
        }
    }

    private void warnRuntime(String path, String detail) {
        if (runtimeFailureWarned.compareAndSet(false, true)) {
            Gloss.warn("real drop " + path + " failed at runtime; neutral fallback applied"
                + (detail == null || detail.isBlank() ? "." : ": " + detail));
        }
    }

    private void validateSamples() {
        validateSample(new RealDropScriptContext(0.0D, 0, 0, 1, 1, false, false, false, false, 0,
            0.0D, -0.4D, 0.0D, 4.0D, 15, 15, 0.25D, "STONE", RealDropModel.ModelKind.BLOCK));
        validateSample(new RealDropScriptContext(1.5D, 30, 1, 3, 32, true, false, false, false, 2,
            0.12D, 0.0D, -0.08D, 0.0D, 7, 0, 0.75D, "TORCH", RealDropModel.ModelKind.FLAT));
        validateSample(new RealDropScriptContext(9.0D, 180, 2, 3, 64, true, true, true, false, 3,
            0.0D, 0.0D, 0.0D, 0.5D, 0, 4, 0.5D, "OAK_SLAB", RealDropModel.ModelKind.THIN));
        validateSample(new RealDropScriptContext(45.0D, 900, 0, 2, 8, false, false, false, true, 11,
            -0.3D, 0.6D, 0.3D, 12.0D, 15, 15, 0.99D, "DIAMOND", RealDropModel.ModelKind.FLAT));
    }

    private void validateSample(RealDropScriptContext context) {
        ScriptScope scope = new ScriptScope(context, variables.size());
        for (ScriptVariable variable : variables) {
            scope.define(variable.name(),
                validateNumber("script.vars." + variable.name(), variable.expression(), scope));
        }
        validateNumber("script.offset.x", offsetX, scope);
        validateNumber("script.offset.y", offsetY, scope);
        validateNumber("script.offset.z", offsetZ, scope);
        validateNumber("script.rotation.x", rotationX, scope);
        validateNumber("script.rotation.y", rotationY, scope);
        validateNumber("script.rotation.z", rotationZ, scope);
        validateNumber("script.scale.x", scaleX, scope);
        validateNumber("script.scale.y", scaleY, scope);
        validateNumber("script.scale.z", scaleZ, scope);
        validateGlow(scope);
        validateBoolean("script.visible", visible, scope);
    }

    private static double validateNumber(String path, Expr expression, ScriptScope scope) {
        Object result = evaluateForValidation(path, expression, scope);
        if (!(result instanceof Double number)) {
            throw new IllegalArgumentException(path + " must evaluate to a number, got " + typeName(result));
        }
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException(path + " result must be finite");
        }
        return number;
    }

    private static void validateBoolean(String path, Expr expression, ScriptScope scope) {
        Object result = evaluateForValidation(path, expression, scope);
        if (!(result instanceof Boolean)) {
            throw new IllegalArgumentException(path + " must evaluate to true or false, got " + typeName(result));
        }
    }

    private void validateGlow(ScriptScope scope) {
        if (glow == null) {
            return;
        }
        color(evaluateForValidation("script.glow", glow, scope));
    }

    private static Object evaluateForValidation(String path, Expr expression, ScriptScope scope) {
        try {
            return ExprEvaluator.eval(expression, scope);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(path + ": " + failure.getMessage());
        }
    }

    private static int color(Object result) {
        if (result instanceof Double number) {
            return Double.isFinite(number) ? (int) (long) (double) number : 0;
        }
        if (result instanceof String text) {
            return parseColor(text.trim());
        }
        throw new IllegalArgumentException(
            "script.glow must evaluate to a colour number or a #RRGGBB string, got " + typeName(result));
    }

    private static int parseColor(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        String digits = text.charAt(0) == '#' ? text.substring(1) : text;
        if (digits.length() != 6 && digits.length() != 8) {
            throw new IllegalArgumentException(
                "script.glow string must be #RRGGBB or #AARRGGBB, got '" + text + "'");
        }
        try {
            long value = Long.parseLong(digits, 16);
            return digits.length() == 6 ? (int) (value | 0xFF000000L) : (int) value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                "script.glow string must be #RRGGBB or #AARRGGBB, got '" + text + "'");
        }
    }

    private static void requireVariableName(String name, Set<String> declared) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("script.vars." + name
                + " is not a valid name; use letters, digits and underscores starting with a letter or underscore");
        }
        if (VARIABLES.contains(name)) {
            throw new IllegalArgumentException("script.vars." + name + " shadows the built-in variable " + name);
        }
        if (declared.contains(name)) {
            throw new IllegalArgumentException("script.vars." + name + " is declared twice");
        }
    }

    private static Expr compile(String path, String source, Set<String> declared, Set<String> referenced) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-blank expression");
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(path + " exceeds " + MAX_SOURCE_LENGTH + " characters");
        }
        Expr expression = parse(path, source);
        validateTree(path, source, expression, declared, referenced);
        return expression;
    }

    private static Expr parse(String path, String source) {
        try {
            return ExprParser.parse(source);
        } catch (ExprException failure) {
            throw new IllegalArgumentException(path + ": " + failure.getMessage()
                + " at position " + failure.position());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(path + ": " + failure.getMessage());
        }
    }

    private static void validateTree(String path, String source, Expr expression, Set<String> declared,
                                     Set<String> referenced) {
        switch (expression) {
            case Expr.Num ignored -> {
            }
            case Expr.Str ignored -> {
            }
            case Expr.Bool ignored -> {
            }
            case Expr.Var variable -> {
                String name = variable.name();
                if (!VARIABLES.contains(name) && !declared.contains(name)) {
                    throw new IllegalArgumentException(path + ": unknown variable '" + name + "' at position "
                        + Math.max(0, source.indexOf(name)));
                }
                referenced.add(name);
            }
            case Expr.ListLiteral list -> {
                for (Expr item : list.items()) {
                    validateTree(path, source, item, declared, referenced);
                }
            }
            case Expr.Unary unary -> validateTree(path, source, unary.operand(), declared, referenced);
            case Expr.Binary binary -> {
                validateTree(path, source, binary.left(), declared, referenced);
                validateTree(path, source, binary.right(), declared, referenced);
            }
            case Expr.Ternary ternary -> {
                validateTree(path, source, ternary.condition(), declared, referenced);
                validateTree(path, source, ternary.ifTrue(), declared, referenced);
                validateTree(path, source, ternary.ifFalse(), declared, referenced);
            }
            case Expr.Call call -> {
                String name = call.name();
                if (!FUNCTIONS.contains(name) && !ExprFunctions.isSupported(name)) {
                    throw new IllegalArgumentException(path + ": unknown function '" + name + "' at position "
                        + Math.max(0, source.indexOf(name)));
                }
                for (Expr argument : call.args()) {
                    validateTree(path, source, argument, declared, referenced);
                }
            }
        }
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double) {
            return "number";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List) {
            return "list";
        }
        return value.getClass().getSimpleName();
    }

    static String normalizeMaterial(String value) {
        String trimmed = value.trim();
        int colon = trimmed.indexOf(':');
        String bare = colon < 0 ? trimmed : trimmed.substring(colon + 1);
        return bare.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    static Pattern globPattern(String glob) {
        Pattern cached = PATTERNS.get(glob);
        if (cached != null) {
            return cached;
        }
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        for (int index = 0; index < glob.length(); index++) {
            char symbol = glob.charAt(index);
            if (symbol == '*') {
                regex.append(".*");
                continue;
            }
            if (symbol == '?') {
                regex.append('.');
                continue;
            }
            regex.append(Pattern.quote(String.valueOf(symbol)));
        }
        Pattern compiled = Pattern.compile(regex.toString());
        if (PATTERNS.size() < MAX_PATTERN_CACHE) {
            PATTERNS.putIfAbsent(glob, compiled);
        }
        return compiled;
    }

    record ScriptVariable(String name, Expr expression) {
    }

    record RealDropScriptContext(
        double t,
        int age,
        int index,
        int count,
        int amount,
        boolean onGround,
        boolean settled,
        boolean inWater,
        boolean inLava,
        int bounces,
        double velocityX,
        double velocityY,
        double velocityZ,
        double height,
        int blockLight,
        int skyLight,
        double random,
        String material,
        RealDropModel.ModelKind kind
    ) {
        static double stableRandom(UUID itemId) {
            long mixed = itemId.getMostSignificantBits() ^ itemId.getLeastSignificantBits();
            mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
            mixed = mixed ^ (mixed >>> 31);
            return (mixed >>> 11) * 0x1.0p-53D;
        }

        double speed() {
            return Math.sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ);
        }
    }

    record RealDropScriptSample(
        double offsetX,
        double offsetY,
        double offsetZ,
        double rotationX,
        double rotationY,
        double rotationZ,
        double scaleX,
        double scaleY,
        double scaleZ,
        int glowArgb,
        boolean visible
    ) {
    }

    private static final class ScriptScope implements ExprScope {
        private final RealDropScriptContext context;
        private final Map<String, Object> locals;

        private ScriptScope(RealDropScriptContext context, int variableCount) {
            this.context = context;
            this.locals = new HashMap<>(Math.max(4, variableCount * 2));
        }

        private void define(String name, double value) {
            locals.put(name, value);
        }

        @Override
        public Object variable(String dottedName) {
            Object local = locals.get(dottedName);
            return local != null ? local : builtin(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return switch (name) {
                case "materialIs" -> materialIs(args);
                case "materialMatches" -> materialMatches(args);
                default -> ExprFunctions.call(name, args);
            };
        }

        private Object builtin(String name) {
            return switch (name) {
                case "t" -> context.t();
                case "age" -> (double) context.age();
                case "index" -> (double) context.index();
                case "count" -> (double) context.count();
                case "amount" -> (double) context.amount();
                case "onGround" -> context.onGround();
                case "settled" -> context.settled();
                case "inWater" -> context.inWater();
                case "inLava" -> context.inLava();
                case "bounces" -> (double) context.bounces();
                case "velocityX" -> context.velocityX();
                case "velocityY" -> context.velocityY();
                case "velocityZ" -> context.velocityZ();
                case "speed" -> context.speed();
                case "height" -> context.height();
                case "blockLight" -> (double) context.blockLight();
                case "skyLight" -> (double) context.skyLight();
                case "random" -> context.random();
                case "material" -> context.material();
                case "isBlock" -> context.kind() == RealDropModel.ModelKind.BLOCK;
                case "isFlat" -> context.kind() == RealDropModel.ModelKind.FLAT;
                case "isThin" -> context.kind() == RealDropModel.ModelKind.THIN;
                case "pi" -> Math.PI;
                default -> null;
            };
        }

        private Object materialIs(List<Object> args) {
            return normalizeMaterial(stringArgument("materialIs", args)).equals(context.material());
        }

        private Object materialMatches(List<Object> args) {
            String glob = normalizeMaterial(stringArgument("materialMatches", args));
            return globPattern(glob).matcher(context.material()).matches();
        }

        private static String stringArgument(String name, List<Object> args) {
            if (args.size() != 1) {
                throw new ExprException(name + " expects 1 argument(s), got " + args.size(), -1);
            }
            if (args.get(0) instanceof String text) {
                return text;
            }
            throw new ExprException(name + " argument 1 must be a string", -1);
        }
    }
}
