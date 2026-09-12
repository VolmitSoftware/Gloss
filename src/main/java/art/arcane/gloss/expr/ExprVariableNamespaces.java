package art.arcane.gloss.expr;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Registry of {@link ExprVariableNamespace}s. Both variable resolvers (the text renderer scope and
 * the condition scope) consult it as their last fallback, so a namespace registered once is visible
 * in every {@code {{ }}}, {@code show} and {@code when} expression. Built-in prefixes are reserved.
 */
public final class ExprVariableNamespaces {
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> RESERVED = Set.of(
        "viewer", "subject", "source", "player", "world", "time", "server", "vars", "metric",
        "entity", "event", "drop", "insight", "item", "inventory");
    private static final ExprVariableNamespaces GLOBAL = new ExprVariableNamespaces();

    private final ConcurrentMap<String, ExprVariableNamespace> namespaces = new ConcurrentHashMap<>();

    public static ExprVariableNamespaces global() {
        return GLOBAL;
    }

    public void register(ExprVariableNamespace namespace) {
        Objects.requireNonNull(namespace, "namespace");
        String prefix = Objects.requireNonNull(namespace.prefix(), "prefix");
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("variable namespace must be a lower-case identifier: " + prefix);
        }
        if (RESERVED.contains(prefix)) {
            throw new IllegalArgumentException("variable namespace " + prefix + " is reserved");
        }
        if (namespaces.putIfAbsent(prefix, namespace) != null) {
            throw new IllegalArgumentException("variable namespace " + prefix + " is already registered");
        }
    }

    public void unregister(String prefix) {
        namespaces.remove(Objects.requireNonNull(prefix, "prefix"));
    }

    public boolean isRegistered(String prefix) {
        return prefix != null && namespaces.containsKey(prefix);
    }

    public Set<String> prefixes() {
        return Set.copyOf(namespaces.keySet());
    }

    /** @return the resolved value, or {@code null} when the prefix is unregistered or the suffix unknown */
    public Object resolve(String dottedName, ExprVariableContext context) {
        if (dottedName == null) {
            return null;
        }
        int dot = dottedName.indexOf('.');
        if (dot <= 0 || dot == dottedName.length() - 1) {
            return null;
        }
        ExprVariableNamespace namespace = namespaces.get(dottedName.substring(0, dot));
        if (namespace == null) {
            return null;
        }
        return namespace.resolve(dottedName.substring(dot + 1), context == null ? ExprVariableContext.empty() : context);
    }

    public void clear() {
        namespaces.clear();
    }
}
