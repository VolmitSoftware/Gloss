package art.arcane.gloss.expr;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Runtime-registered expression functions. The built-in library in {@link ExprFunctions} stays a
 * static switch; anything a feature lane adds ({@code lang}, {@code px}, ...) registers here with a
 * signature, so the condition compiler can type-check calls and every scope can resolve them as a
 * last fallback. Registration happens during {@link art.arcane.gloss.service.GlossService#contribute()},
 * before any document is parsed.
 */
public final class ExprFunctionRegistry {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ExprFunctionRegistry GLOBAL = new ExprFunctionRegistry();

    private final ConcurrentMap<String, Spec> specs = new ConcurrentHashMap<>();

    public enum Kind {
        NUMBER,
        STRING,
        BOOLEAN,
        LIST,
        ANY
    }

    @FunctionalInterface
    public interface Implementation {
        Object call(ExprScope scope, List<Object> args);
    }

    /**
     * @param parameters the fixed parameter kinds; with {@code variadic} the last kind repeats
     */
    public record Spec(String name, Kind returns, List<Kind> parameters, boolean variadic,
                       Implementation implementation) {
        public Spec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(returns, "returns");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            Objects.requireNonNull(implementation, "implementation");
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("function name must be a plain identifier: " + name);
            }
            if (variadic && parameters.isEmpty()) {
                throw new IllegalArgumentException("variadic function " + name + " needs at least one parameter kind");
            }
        }

        public boolean accepts(int argumentCount) {
            return variadic ? argumentCount >= parameters.size() - 1 : argumentCount == parameters.size();
        }

        public Kind parameter(int index) {
            if (parameters.isEmpty()) {
                throw new IndexOutOfBoundsException(name + " takes no arguments");
            }
            return index < parameters.size() ? parameters.get(index) : parameters.get(parameters.size() - 1);
        }
    }

    public static boolean isSupported(String name) {
        return ExprFunctions.isBuiltIn(name) || GLOBAL.contains(name);
    }

    public static ExprFunctionRegistry global() {
        return GLOBAL;
    }

    public void register(Spec spec) {
        Objects.requireNonNull(spec, "spec");
        if (ExprFunctions.isBuiltIn(spec.name())) {
            throw new IllegalArgumentException("function " + spec.name() + " shadows a built-in");
        }
        if (specs.putIfAbsent(spec.name(), spec) != null) {
            throw new IllegalArgumentException("function " + spec.name() + " is already registered");
        }
    }

    public void unregister(String name) {
        specs.remove(Objects.requireNonNull(name, "name"));
    }

    public Spec find(String name) {
        return name == null ? null : specs.get(name);
    }

    public boolean contains(String name) {
        return name != null && specs.containsKey(name);
    }

    public Set<String> names() {
        return Set.copyOf(specs.keySet());
    }

    /** @return the call result, or {@code null} when no function of that name is registered */
    public Object call(ExprScope scope, String name, List<Object> args) {
        Spec spec = find(name);
        if (spec == null) {
            return null;
        }
        if (!spec.accepts(args.size())) {
            throw new ExprException(name + " expects " + (spec.variadic() ? "at least " + (spec.parameters().size() - 1)
                : String.valueOf(spec.parameters().size())) + " argument(s), got " + args.size(), -1);
        }
        return spec.implementation().call(scope, args);
    }

    public void clear() {
        specs.clear();
    }
}
