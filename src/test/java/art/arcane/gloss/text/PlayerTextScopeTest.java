package art.arcane.gloss.text;

import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerTextScopeTest {
    @Test
    void eachRoleUsesItsOwnIdentityAndUsernameRemainsRaw() {
        Player viewer = player("Reader");
        Player subject = player("Subject");
        Player sender = player("Sender");
        ExprScope underlying = new TestScope(new ExprVariableContext(viewer, subject, sender, null));
        PlayerTextScope scope = new PlayerTextScope(underlying, viewer,
            (reader, target) -> "[VIP] " + target.getName());

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
