package art.arcane.gloss.text;

import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprRoleSnapshot;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerTextScopeTest {
    @Test
    void eachRoleUsesItsOwnIdentityAndUsernameRemainsRaw() {
        Player viewer = player("Reader");
        Player subject = player("Subject");
        Player sender = player("Sender");
        ExprScope underlying = new TestScope(new ExprVariableContext(viewer, subject, sender, null));
        PlayerTextScope scope = new PlayerTextScope(underlying, viewer,
            (reader, target) -> "[VIP] " + target.getName(),
            (role, captured) -> "[VIP] " + captured.variableContext().roleValue(role, "name"));

        assertEquals("[VIP] Reader", scope.variable("player.name"));
        assertEquals("[VIP] Reader", scope.variable("viewer.name"));
        assertEquals("[VIP] Subject", scope.variable("subject.name"));
        assertEquals("[VIP] Sender", scope.variable("source.name"));
        assertEquals("[VIP] Sender", scope.variable("sender.name"));
        assertEquals("Sender", scope.variable("sender.username"));
        assertEquals("Subject", underlying.variable("subject.name"));
        assertEquals("[VIP] Reader", scope.call("papi", List.of("player_name")));
        assertEquals("[VIP] Sender", scope.call("papi", List.of("sender", "player_name", "")));
    }

    @Test
    void capturedRolesUseFormattedNamesWithoutReadingTheLiveSubject() {
        ExprRoleSnapshot snapshot = new ExprRoleSnapshot() {
            @Override
            public Object variable(String property) {
                return "Subject";
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return null;
            }

            @Override
            public boolean ownsCurrentThread() {
                return false;
            }
        };
        Player subject = player("Unreadable");
        ExprScope underlying = new TestScope(new ExprVariableContext(null, subject, null, null,
            Map.of("subject", snapshot)));
        PlayerTextScope scope = new PlayerTextScope(underlying, null,
            (viewer, target) -> { throw new AssertionError("Live subject access"); },
            (role, captured) -> "[VIP] " + captured.variableContext().roleValue(role, "name"));

        assertEquals("[VIP] Subject", scope.variable("subject.name"));
        assertEquals("[VIP] Subject", scope.variable("subject.displayName"));
        assertEquals("Subject", scope.variable("subject.username"));
        assertEquals("[VIP] Subject", scope.call("papi", List.of("subject", "player_name", "")));
    }

    private static Player player(String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> method.getName().equals("getName") ? name : null);
    }

    private record TestScope(ExprVariableContext variableContext) implements ExprScope {
        @Override
        public Object variable(String name) {
            return name.equals("subject.name") ? variableContext.subject().getName() : null;
        }

        @Override
        public Object call(String name, List<Object> args) {
            return null;
        }
    }
}
