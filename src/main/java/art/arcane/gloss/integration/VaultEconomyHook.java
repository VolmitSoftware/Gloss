package art.arcane.gloss.integration;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault's economy, looked up lazily and re-looked-up whenever it is missing, so an economy plugin
 * that enables after Gloss is still found. Every call fails closed: no economy means no purchase,
 * never a free one.
 */
public final class VaultEconomyHook {
    private volatile Economy economy;

    public VaultEconomyHook() {
        this.economy = lookup();
    }

    public boolean available() {
        return provider() != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        Economy provider = provider();
        if (provider == null || player == null) {
            return false;
        }
        try {
            return provider.has(player, amount);
        } catch (Throwable failure) {
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        Economy provider = provider();
        if (provider == null || player == null) {
            return false;
        }
        try {
            return provider.withdrawPlayer(player, amount).transactionSuccess();
        } catch (Throwable failure) {
            return false;
        }
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        Economy provider = provider();
        if (provider == null || player == null) {
            return false;
        }
        try {
            return provider.depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable failure) {
            return false;
        }
    }

    private Economy provider() {
        Economy current = economy;
        if (current != null) {
            return current;
        }
        current = lookup();
        economy = current;
        return current;
    }

    private static Economy lookup() {
        try {
            if (Bukkit.getServer() == null) {
                return null;
            }
            RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable absent) {
            return null;
        }
    }
}
