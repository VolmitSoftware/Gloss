package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.EconomyActionData;
import art.arcane.gloss.config.action.GiveActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.TakeActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three shop actions: what they parse, and which of them stop the list. A failed take or a
 * failed withdraw must stop, because everything after it in a shop's list is the reward.
 */
class GiveTakeActionTest {

    @Test
    void giveParsesItsItemAmountAndFullInventoryChoice() {
        GiveActionData give = (GiveActionData) action("""
            { "type": "give", "item": { "type": "item", "item": "minecraft:diamond" },
              "amount": "3", "dropIfFull": false }
            """);
        assertEquals(MenuActionType.GIVE, give.getType());
        assertEquals("3", give.amount());
        assertEquals(Boolean.FALSE, give.dropIfFull());
        assertNull(give.invalidReason());
    }

    @Test
    void giveDropsAtTheFeetByDefault() {
        GiveActionData give = (GiveActionData) action("""
            { "type": "give", "item": { "type": "item", "item": "minecraft:diamond" } }
            """);
        assertTrue(give.dropIfFullOrDefault());
        assertEquals("1", give.amount());
    }

    @Test
    void giveWithoutAnItemCanNeverDoAnything() {
        GiveActionData give = (GiveActionData) action("{ \"type\": \"give\" }");
        assertNotNull(give.invalidReason());
    }

    @Test
    void takeParsesItsDenyMessage() {
        TakeActionData take = (TakeActionData) action("""
            { "type": "take", "item": { "type": "item", "item": "minecraft:emerald" },
              "amount": "5", "denyMessage": "&cYou need five emeralds." }
            """);
        assertEquals(MenuActionType.TAKE, take.getType());
        assertEquals("&cYou need five emeralds.", take.denyMessage());
        assertNull(take.invalidReason());
    }

    @Test
    void economyParsesItsOperation() {
        EconomyActionData economy = (EconomyActionData) action("""
            { "type": "economy", "op": "withdraw", "amount": "100", "denyMessage": "&cNot enough." }
            """);
        assertEquals(MenuActionType.ECONOMY, economy.getType());
        assertEquals("withdraw", economy.op());
        assertEquals("100", economy.amount());
    }

    @Test
    void anUnknownEconomyOperationIsRefused() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> action("{ \"type\": \"economy\", \"op\": \"transfer\", \"amount\": \"1\" }"));
        assertTrue(rootMessage(failure).contains("transfer"), rootMessage(failure));
    }

    @Test
    void amountsResolveThroughTheirExpression() {
        assertEquals(3, ShopAmounts.resolve("3", null));
        assertEquals(1, ShopAmounts.resolve(null, null));
        assertEquals(1, ShopAmounts.resolve("nonsense", null), "a broken amount is one, never zero");
        assertEquals(1, ShopAmounts.resolve("-4", null), "an amount is never negative");
    }

    @Test
    void aDepositNeverStopsTheListAndAWithdrawCanOnlyStopOnFailure() {
        EconomyActionData deposit = (EconomyActionData) action(
            "{ \"type\": \"economy\", \"op\": \"deposit\", \"amount\": \"10\" }");
        assertTrue(EconomyMenuAction.continuesOnFailure(deposit.op()));

        EconomyActionData withdraw = (EconomyActionData) action(
            "{ \"type\": \"economy\", \"op\": \"withdraw\", \"amount\": \"10\" }");
        assertTrue(!EconomyMenuAction.continuesOnFailure(withdraw.op()));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = failure.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? "" : message;
    }

    private static MenuActionData action(String raw) {
        return DocumentParsers.GSON.fromJson(raw, MenuActionData.class);
    }
}
