package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.GlossMenuClickEvent;
import art.arcane.gloss.api.GlossMenuOpenEvent;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * One MONITOR listener for every Bukkit-backed trigger. Handlers do a map lookup and hand the
 * event to the service on the thread the event arrived on (the player's region); chat arrives
 * asynchronously and is moved onto the sender's region first. Menu, dialog and inventory events
 * that other lanes publish are subscribed by class name so this lane runs without them.
 */
final class BehaviorTriggers implements Listener {
    private static final String MENU_CLOSE_EVENT = "art.arcane.gloss.api.GlossMenuCloseEvent";
    private static final String DIALOG_SUBMIT_EVENT = "art.arcane.gloss.api.GlossDialogSubmitEvent";
    private static final String INVENTORY_CLICK_EVENT = "art.arcane.gloss.api.GlossInventoryClickEvent";

    private final Gloss plugin;
    private final BehaviorService service;
    private boolean chatHooked;

    BehaviorTriggers(Gloss plugin, BehaviorService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (!chatHooked && plugin.chat() != null) {
            plugin.chat().addChatHook(this::onChat);
            chatHooked = true;
        }
        subscribeOptional(MENU_CLOSE_EVENT, BehaviorTrigger.MENU_CLOSE, "getMenuId", null);
        subscribeOptional(DIALOG_SUBMIT_EVENT, BehaviorTrigger.DIALOG_SUBMIT, "getDialogId", null);
        subscribeOptional(INVENTORY_CLICK_EVENT, BehaviorTrigger.INVENTORY_CLICK, "getInventoryId", "getSlot");
    }

    void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * Held until the player's state file has landed. A {@code join} entry gated on
     * {@code state.visits == 0} would otherwise read the declared default and play the first-visit
     * scene on every join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean firstJoin = !player.hasPlayedBefore();
        service.whenStateReady(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            service.fire(BehaviorTrigger.JOIN, TriggerEvent.viewer(player));
            if (firstJoin) {
                service.fire(BehaviorTrigger.FIRST_JOIN, TriggerEvent.viewer(player));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        service.fire(BehaviorTrigger.QUIT, TriggerEvent.viewer(player));
        service.forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        service.fire(BehaviorTrigger.RESPAWN, TriggerEvent.viewer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        EntityDamageEvent cause = victim.getLastDamageCause();
        service.fire(BehaviorTrigger.DEATH, TriggerEvent.death(victim, victim.getKiller(), causeKey(cause)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && service.subscriptions().hasAny(BehaviorTrigger.KILL)) {
            service.fire(BehaviorTrigger.KILL, TriggerEvent.kill(killer, event.getEntity()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!service.subscriptions().hasAny(BehaviorTrigger.DAMAGE)) {
            return;
        }
        TriggerEvent trigger = TriggerEvent.damage(event.getEntity(), event.getDamager(), event.getFinalDamage(),
            causeKey(event));
        if (trigger.viewer() != null) {
            service.fire(BehaviorTrigger.DAMAGE, trigger);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        service.fire(BehaviorTrigger.BLOCK_BREAK, TriggerEvent.block(event.getPlayer(),
            event.getBlock().getType().getKey().toString(), event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        service.fire(BehaviorTrigger.BLOCK_PLACE, TriggerEvent.block(event.getPlayer(),
            event.getBlockPlaced().getType().getKey().toString(), event.getBlockPlaced().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            service.fire(BehaviorTrigger.PICKUP, TriggerEvent.item(player,
                event.getItem().getItemStack().getType().getKey().toString(), event.getItem().getItemStack().getAmount()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        service.fire(BehaviorTrigger.DROP, TriggerEvent.item(event.getPlayer(),
            event.getItemDrop().getItemStack().getType().getKey().toString(), event.getItemDrop().getItemStack().getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        service.forgetRegions(event.getPlayer().getUniqueId());
        service.fire(BehaviorTrigger.WORLD_CHANGE, TriggerEvent.worldChange(event.getPlayer(),
            event.getFrom().getName(), event.getPlayer().getWorld().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        service.fire(BehaviorTrigger.SERVER_START, TriggerEvent.none());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMenuOpen(GlossMenuOpenEvent event) {
        service.fire(BehaviorTrigger.MENU_OPEN, TriggerEvent.menu(event.getPlayer(), event.getMenuId(), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMenuClick(GlossMenuClickEvent event) {
        service.fire(BehaviorTrigger.MENU_CLICK, TriggerEvent.menu(event.getPlayer(), event.getMenuId(),
            event.getComponentId()));
    }

    private void onChat(Player player, String message) {
        if (!service.subscriptions().hasAny(BehaviorTrigger.CHAT)) {
            return;
        }
        FoliaScheduler.runEntity(plugin, player, () -> service.fire(BehaviorTrigger.CHAT, TriggerEvent.chat(player, message)));
    }

    @SuppressWarnings("unchecked")
    private void subscribeOptional(String className, BehaviorTrigger trigger, String idGetter, String secondaryGetter) {
        Class<?> type;
        Method player;
        Method id;
        Method secondary;
        try {
            type = Class.forName(className);
            if (!Event.class.isAssignableFrom(type)) {
                return;
            }
            player = type.getMethod("getPlayer");
            id = type.getMethod(idGetter);
            secondary = secondaryGetter == null ? null : type.getMethod(secondaryGetter);
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            Gloss.verbose("Behavior trigger %s has no event class yet (%s); it stays inert.", trigger.key(), className);
            return;
        }
        EventExecutor executor = (listener, event) -> {
            if (event instanceof Cancellable cancellable && cancellable.isCancelled()) {
                return;
            }
            try {
                Player viewer = (Player) player.invoke(event);
                String selector = String.valueOf(id.invoke(event));
                String component = secondary == null ? null : String.valueOf(secondary.invoke(event));
                service.fire(trigger, TriggerEvent.menu(viewer, selector, component));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "behavior-optional-event", failure,
                    "Behavior trigger %s could not read %s.", trigger.key(), className);
            }
        };
        Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, this, EventPriority.MONITOR, executor, plugin, true);
    }

    private static String causeKey(EntityDamageEvent event) {
        return event == null ? "unknown" : event.getCause().name().toLowerCase(Locale.ROOT);
    }
}
