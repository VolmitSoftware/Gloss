package art.arcane.gloss.condition;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class EntityRoleSnapshotRuntime implements RoleSnapshotStore.Runtime {
    private final Gloss plugin;
    private final BooleanSupplier enabled;

    public EntityRoleSnapshotRuntime(Gloss plugin, BooleanSupplier enabled) {
        this.plugin = Objects.requireNonNull(plugin);
        this.enabled = Objects.requireNonNull(enabled);
    }

    @Override
    public boolean dispatch(Entity entity, Runnable task, Runnable retired) {
        return FoliaScheduler.runEntity(plugin, entity, task, 0L, retired);
    }

    @Override
    public ExprScope scope(Entity entity) {
        Player player = entity instanceof Player active ? active : null;
        return new GlossConditionScope(plugin,
            new GlossConditionContext(player, entity, entity, entity.getLocation(), Map.of()));
    }

    @Override
    public boolean active(Entity entity) {
        return enabled.getAsBoolean() && (entity instanceof Player player ? player.isOnline() : entity.isValid());
    }

    @Override
    public boolean owns(Entity entity) {
        return FoliaScheduler.isOwnedByCurrentRegion(entity);
    }

    @Override
    public boolean current(Entity entity) {
        return !(entity instanceof Player) || Bukkit.getPlayer(entity.getUniqueId()) == entity;
    }
}
