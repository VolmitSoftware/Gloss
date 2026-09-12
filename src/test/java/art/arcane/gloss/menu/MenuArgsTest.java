package art.arcane.gloss.menu;

import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.enums.MenuActionCommandSource;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.CommandMenuAction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menu arguments, from the command line to the console. A {@code server} command built out of
 * argument text is console authority handed to whoever clicked, so the characters that chain a
 * second command are refused rather than escaped.
 */
class MenuArgsTest {

    @Test
    void keyValuePairsParseInOrder() {
        assertEquals(Map.of("tier", "gold", "page", "2"), MenuArguments.parse("tier=gold page=2"));
    }

    @Test
    void anEmptyArgumentStringIsNoArguments() {
        assertEquals(Map.of(), MenuArguments.parse(""));
        assertEquals(Map.of(), MenuArguments.parse(null));
        assertEquals(Map.of(), MenuArguments.parse("   "));
    }

    @Test
    void aValueMayContainAnEqualsSign() {
        assertEquals(Map.of("filter", "a=b"), MenuArguments.parse("filter=a=b"));
    }

    @Test
    void aTokenWithNoEqualsSignIsRefused() {
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> MenuArguments.parse("tier gold"));
        assertTrue(failure.getMessage().contains("tier"), failure.getMessage());
    }

    @Test
    void aKeyMustBeAnIdentifier() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MenuArguments.parse("Tier-1=gold"));
    }

    @Test
    void consoleCommandsRefuseTextThatCouldChainASecondCommand() {
        assertTrue(CommandMenuAction.consoleSafe("give Notch stone 1"));
        assertFalse(CommandMenuAction.consoleSafe("say hi; op Notch"));
        assertFalse(CommandMenuAction.consoleSafe("say hi\nop Notch"));
        assertFalse(CommandMenuAction.consoleSafe("say hi /op Notch"));
    }

    @Test
    void expressionSpansResolveAgainstTheScopeAroundThem() {
        ExprScope scope = new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return "args.tier".equals(dottedName) ? "gold" : null;
            }

            @Override
            public Object call(String name, java.util.List<Object> args) {
                return null;
            }
        };
        assertEquals("give Notch gold_ingot 1",
            MenuExpressions.substitute("give Notch {{ args.tier }}_ingot 1", scope));
        assertEquals("give Notch stone", MenuExpressions.substitute("give Notch stone", scope));
    }

    @Test
    void anExpressionThatCannotResolveLeavesItsSpanAlone() {
        ExprScope empty = new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return null;
            }

            @Override
            public Object call(String name, java.util.List<Object> args) {
                return null;
            }
        };
        assertEquals("give Notch {{ args.tier }}",
            MenuExpressions.substitute("give Notch {{ args.tier }}", empty));
    }

    @Test
    void aPlayerSourcedCommandIsNotSubjectToTheConsoleGuard() {
        CommandActionData data = new CommandActionData(MenuActionCommandSource.PLAYER, "say hi; hello",
            null, null, null);
        assertEquals(MenuActionCommandSource.PLAYER, data.sourceOrDefault());
        assertTrue(new CommandMenuAction(data).hasCommand());
    }
}
