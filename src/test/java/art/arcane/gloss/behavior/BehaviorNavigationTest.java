package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorNavigationTest {
    private static final BehaviorRuntime RUNTIME = BehaviorRuntime.compile("nav", BehaviorDoc.parse("nav.json",
        "{\"schemaVersion\":2,\"revision\":1,\"on\":[{\"trigger\":\"chat\","
            + "\"do\":[{\"type\":\"navigate\",\"mode\":\"push\",\"target\":\"shops/confirm\"}]}]}"));

    @Test
    void aFiringWithAPlayerNavigatesThatPlayersMenu() throws ReflectiveOperationException {
        Player player = player();
        List<NavigationRequest> requests = new ArrayList<>();
        BehaviorActionContext context = context(TriggerEvent.viewer(player), (viewer, request) -> {
            assertSame(player, viewer);
            requests.add(request);
            return NavigationResult.APPLIED;
        });

        MenuAction.execute(RUNTIME.entries().getFirst().actions(), context);

        assertEquals(List.of(new NavigationRequest(NavigationMode.PUSH, "shops/confirm")), requests);
    }

    @Test
    void theNavigationResultReachesTheCaller() throws ReflectiveOperationException {
        BehaviorActionContext context = context(TriggerEvent.viewer(player()),
            (viewer, request) -> NavigationResult.NOT_FOUND);

        assertEquals(NavigationResult.NOT_FOUND, context.navigate(new NavigationRequest(NavigationMode.PUSH, "missing")));
    }

    @Test
    void aFiringWithoutAPlayerIsDenied() throws ReflectiveOperationException {
        List<Player> calls = new ArrayList<>();
        BehaviorActionContext context = context(TriggerEvent.none(), (viewer, request) -> {
            calls.add(viewer);
            return NavigationResult.APPLIED;
        });

        assertEquals(NavigationResult.DENIED, context.navigate(new NavigationRequest(NavigationMode.CLOSE, null)));
        assertTrue(calls.isEmpty());
    }

    private static BehaviorActionContext context(TriggerEvent event,
                                                 BiFunction<Player, NavigationRequest, NavigationResult> navigator)
        throws ReflectiveOperationException {
        Gloss gloss = CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of()));
        return new BehaviorActionContext(gloss, RUNTIME, RUNTIME.entries().getFirst(), event, navigator);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(BehaviorNavigationTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getLocation" -> null;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
