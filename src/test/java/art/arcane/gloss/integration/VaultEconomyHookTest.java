package art.arcane.gloss.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What the economy hook does with no Vault on the server. Failing closed is the whole contract: a
 * shop that cannot take payment must not report that it did.
 */
class VaultEconomyHookTest {

    @Test
    void withNoEconomyEveryCallFailsClosed() {
        VaultEconomyHook hook = new VaultEconomyHook();
        assertFalse(hook.available());
        assertFalse(hook.has(null, 10D));
        assertFalse(hook.withdraw(null, 10D));
        assertFalse(hook.deposit(null, 10D));
    }
}
