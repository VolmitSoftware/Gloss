package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.text.TextPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tablist driver applied every online player in one tick, resolved the Vault primary group for
 * each of them whether or not the format used it, and re-parsed the format to decide the cadence.
 * These pin the replacements: a striped sweep, and per-revision list-name traits.
 */
class TablistSweepStripeTest {
    @AfterEach
    void resetEmojiState() {
        TextPipeline.publishConditionalEmojiTokens(List.of());
    }

    @Test
    void aOneTickIntervalSweepsTheWholeFleetWithoutBuildingACycle() {
        assertTrue(TablistService.sweepsWholeFleet(1));
        assertTrue(TablistService.sweepsWholeFleet(0));
        assertFalse(TablistService.sweepsWholeFleet(2));
        assertFalse(TablistService.sweepsWholeFleet(40));
    }

    @Test
    void aConditionalEmojiInTheFormatMovesTheFastCadenceWithTheRegistry() {
        TablistRuntime runtime = TablistRuntime.compile(doc("$player &7:vip:"));
        TablistRuntime.ListNameProfile profile = runtime.listName(scope(), BoundedConditionErrorCallback.silent());

        assertFalse(profile.fastRefresh());
        TextPipeline.publishConditionalEmojiTokens(List.of(":vip:"));
        assertTrue(profile.fastRefresh(), "a conditional emoji must promote the player");
        TextPipeline.publishConditionalEmojiTokens(List.of());
        assertFalse(profile.fastRefresh(),
            "removing it must release every player from the per-tick driver");
    }

    @Test
    void aFullCycleAppliesEveryPlayerExactlyOnce() {
        for (int players = 0; players <= 64; players++) {
            for (int interval = 1; interval <= 40; interval++) {
                int cursor = 0;
                for (int stripe = 0; stripe < interval; stripe++) {
                    cursor += TablistService.stripeSize(players - cursor, interval - stripe);
                }
                assertEquals(players, cursor, "players=" + players + " interval=" + interval);
            }
        }
    }

    @Test
    void oneThousandPlayersOnTheDefaultIntervalSpreadAcrossTheWholeInterval() {
        assertEquals(25, TablistService.stripeSize(1000, 40));
        assertEquals(0, TablistService.stripeSize(0, 40));
    }

    @Test
    void aFormatWithoutTheGroupTokenNeverAsksForTheGroup() {
        TablistRuntime runtime = TablistRuntime.compile(doc("$player"));

        TablistRuntime.ListNameProfile profile = runtime.listName(scope(), BoundedConditionErrorCallback.silent());

        assertFalse(profile.usesGroup());
        assertFalse(profile.fastRefresh());
    }

    @Test
    void aFormatThatSplicesTheGroupIsMarkedOnce() {
        TablistRuntime runtime = TablistRuntime.compile(doc("&7[$group] &f$player"));

        assertTrue(runtime.listName(scope(), BoundedConditionErrorCallback.silent()).usesGroup());
    }

    @Test
    void anAnimatedFormatIsMarkedFastWithoutReparsingPerPlayer() {
        TablistRuntime runtime = TablistRuntime.compile(
            doc("{{ wave('$player', ['&a', '&b'], time.ticks) }}"));

        TablistRuntime.ListNameProfile first = runtime.listName(scope(), BoundedConditionErrorCallback.silent());
        TablistRuntime.ListNameProfile second = runtime.listName(scope(), BoundedConditionErrorCallback.silent());

        assertTrue(first.fastRefresh());
        assertSame(first, second, "the profile is a document value, not a per-player allocation");
    }

    @Test
    void variantsCarryTheirOwnTraits() {
        TablistDoc doc = new TablistDoc(2, 1L, ShowCondition.ALWAYS,
            new TablistDoc.HeaderFooter(false, ShowCondition.ALWAYS,
                new TablistDoc.HeaderFooterPresentation("", ""), List.of()),
            new TablistDoc.ListNames(true, ShowCondition.ALWAYS,
                new TablistDoc.ListNamePresentation("$player"),
                List.of(new TablistDoc.ListNameVariant("staff", 10, "viewer.level > 5",
                    new TablistDoc.ListNamePresentation("&c[$group] |animation.rainbow| $player")))));
        TablistRuntime runtime = TablistRuntime.compile(doc);

        TablistRuntime.ListNameProfile staff = runtime.listName(
            new TestScope(Map.of("viewer.level", 9.0D)), BoundedConditionErrorCallback.silent());
        TablistRuntime.ListNameProfile base = runtime.listName(
            new TestScope(Map.of("viewer.level", 1.0D)), BoundedConditionErrorCallback.silent());

        assertTrue(staff.usesGroup());
        assertTrue(staff.fastRefresh());
        assertFalse(base.usesGroup());
        assertFalse(base.fastRefresh());
    }

    private static TablistDoc doc(String format) {
        return new TablistDoc(2, 1L, ShowCondition.ALWAYS,
            new TablistDoc.HeaderFooter(false, ShowCondition.ALWAYS,
                new TablistDoc.HeaderFooterPresentation("", ""), List.of()),
            new TablistDoc.ListNames(true, ShowCondition.ALWAYS,
                new TablistDoc.ListNamePresentation(format), List.of()));
    }

    private static ExprScope scope() {
        return new TestScope(Map.of());
    }

    private record TestScope(Map<String, Object> variables) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            return variables.get(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    }
}
