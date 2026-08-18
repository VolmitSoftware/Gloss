package art.arcane.gloss.group;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

final class VaultPermissionHook {
    private volatile Permission permission;

    VaultPermissionHook() {
        this.permission = lookup();
    }

    String primaryGroup(Player player) {
        Permission provider = permission;
        if (provider == null) {
            provider = lookup();
            permission = provider;
        }
        if (provider == null) {
            return null;
        }
        try {
            return provider.getPrimaryGroup(player);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Permission lookup() {
        RegisteredServiceProvider<Permission> registration = Bukkit.getServicesManager().getRegistration(Permission.class);
        return registration == null ? null : registration.getProvider();
    }
}
