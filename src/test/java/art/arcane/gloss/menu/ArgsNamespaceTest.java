package art.arcane.gloss.menu;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.behavior.ArgsView;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * One {@code args} namespace serves both readers. A behavior program installs its trigger arguments
 * for the thread it runs on, so text a behavior renders through another document's scope resolves
 * {@code args.<name>}; everything else still reads the open surface's arguments.
 */
class ArgsNamespaceTest {
    private final ArgsNamespace namespace = new ArgsNamespace();

    @AfterEach
    void cleanUp() {
        ArgsView.exit(null);
    }

    @Test
    void thePrefixIsClaimedOnceForBothReaders() {
        assertEquals("args", namespace.prefix());
        assertEquals(ArgsView.PREFIX, namespace.prefix());
    }

    @Test
    void aRunningBehaviorProgramsTriggerArgumentsResolve() {
        assertNull(namespace.resolve("quest", null));

        Map<String, Object> previous = ArgsView.enter(program(Map.of("quest", "mill")));
        try {
            assertEquals("mill", namespace.resolve("quest", null));
            assertNull(namespace.resolve("absent", null));
        } finally {
            ArgsView.exit(previous);
        }

        assertNull(namespace.resolve("quest", null),
            "the arguments leave with the program that installed them");
    }

    private static ActionContext program(Map<String, Object> args) {
        return new ProgramContext(args);
    }

    private record ProgramContext(Map<String, Object> args) implements ActionContext, ArgsView.Source {
        @Override
        public Player player() {
            return null;
        }

        @Override
        public String menuId() {
            return "behaviors";
        }

        @Override
        public String componentId() {
            return "entry";
        }

        @Override
        public HoloClickTrigger trigger() {
            return HoloClickTrigger.ANY;
        }

        @Override
        public NavigationResult navigate(NavigationRequest request) {
            return NavigationResult.DENIED;
        }

        @Override
        public ExprScope conditionScope() {
            return new ExprScope() {
                @Override
                public Object variable(String dottedName) {
                    return null;
                }

                @Override
                public Object call(String name, List<Object> arguments) {
                    return null;
                }
            };
        }
    }
}
