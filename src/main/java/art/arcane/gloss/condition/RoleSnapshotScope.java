package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprRoleSnapshot;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespaces;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RoleSnapshotScope implements ExprScope {
    private static final Set<String> ROLE_FUNCTIONS = Set.of(
        "hasPermission", "inGroup", "inRegion", "isBedrock", "papi", "papiNumber");

    private final ExprScope viewerScope;
    private final ExprVariableContext context;

    public RoleSnapshotScope(ExprScope viewerScope, ExprVariableContext context) {
        this.viewerScope = Objects.requireNonNull(viewerScope);
        this.context = Objects.requireNonNull(context);
    }

    @Override
    public Object variable(String name) {
        int separator = name.indexOf('.');
        if (separator > 0) {
            String role = name.substring(0, separator);
            ExprRoleSnapshot snapshot = context.snapshots().get(role);
            if (snapshot != null) {
                String property = name.substring(separator + 1);
                if (property.equals("username") || property.equals("displayName")) {
                    property = "name";
                }
                return snapshot.variable("subject." + property);
            }
            if (role.equals("world") && context.snapshots().containsKey("subject")) {
                return context.snapshots().get("subject").variable(name);
            }
            if (ExprVariableNamespaces.global().isRegistered(role)) {
                return ExprVariableNamespaces.global().resolve(name, context);
            }
        }
        return viewerScope.variable(name);
    }

    @Override
    public Object call(String name, List<Object> arguments) {
        if (ROLE_FUNCTIONS.contains(name) && !arguments.isEmpty()) {
            String role = String.valueOf(arguments.getFirst());
            ExprRoleSnapshot snapshot = context.snapshots().get(role);
            if (snapshot != null) {
                List<Object> normalized = new ArrayList<>(arguments);
                normalized.set(0, "subject");
                return snapshot.call(name, normalized);
            }
            return viewerScope.call(name, arguments);
        }
        if (ExprFunctionRegistry.global().contains(name)) {
            return ExprFunctionRegistry.global().call(this, name, arguments);
        }
        Object builtin = ExprFunctions.call(name, arguments);
        return builtin == null ? viewerScope.call(name, arguments) : builtin;
    }

    @Override
    public ExprVariableContext variableContext() {
        return context;
    }
}
