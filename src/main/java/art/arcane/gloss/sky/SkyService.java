package art.arcane.gloss.sky;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.service.PaperBridges;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-viewer time, weather and world border. Overrides stack by purpose so two features can hold
 * the sky at once, and every exit path a player has — quit, death, world change, plugin disable,
 * and a crash caught by the journal on next join — puts the real sky back.
 */
public final class SkyService implements GlossService, Listener {
    public static final String NAME = "sky";
    public static final String SECTION = "sky";
    static final int FADE_INTERVAL_TICKS = 2;

    private record Fade(UUID player, long from, long to, int fadeTicks, int elapsedTicks) {
        private Fade advanced(int ticks) {
            return new Fade(player, from, to, fadeTicks, Math.min(fadeTicks, elapsedTicks + ticks));
        }

        private boolean done() {
            return elapsedTicks >= fadeTicks;
        }
    }

    private final Gloss plugin;
    private final PlayerSections sections;
    private final SkyOwnershipStack stack = new SkyOwnershipStack();
    private final ConcurrentMap<UUID, Fade> fades = new ConcurrentHashMap<>();
    private final BorderApplier borders;
    private int fadeTaskId = -1;

    public SkyService(Gloss plugin, PlayerSections sections) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.borders = PaperBridges.load("org.bukkit.entity.Player",
            "art.arcane.gloss.paper.PaperWorldBorderBridge", BorderApplier.class)
            .orElseGet(PacketWorldBorder::new);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (fadeTaskId == -1) {
            fadeTaskId = plugin.scheduler().sr(() -> tickFades(FADE_INTERVAL_TICKS), FADE_INTERVAL_TICKS);
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (fadeTaskId != -1) {
            plugin.scheduler().csr(fadeTaskId);
            fadeTaskId = -1;
        }
        for (UUID playerId : stack.players()) {
            Player viewer = Bukkit.getPlayer(playerId);
            if (viewer != null) {
                restoreReal(viewer);
            }
        }
        stack.clear();
        fades.clear();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().sky().equals(next.modules().sky());
    }

    public boolean enabled() {
        return plugin.cfg().modules().sky().enabled();
    }

    /** Claims this viewer's sky under the override's purpose and shows it. */
    public void apply(Player viewer, SkyOverride override) {
        stack.push(viewer.getUniqueId(), override);
        journal(viewer.getUniqueId());
        show(viewer, override);
    }

    /** Hands the sky back to the next owner down, or to the real world when there is none. */
    public void release(Player viewer, String purpose) {
        UUID viewerId = viewer.getUniqueId();
        stack.release(viewerId, purpose);
        journal(viewerId);
        Optional<SkyOverride> next = stack.top(viewerId);
        if (next.isPresent()) {
            show(viewer, next.get());
            return;
        }
        fades.remove(viewerId);
        restoreReal(viewer);
    }

    public Optional<SkyOverride> top(UUID viewerId) {
        return stack.top(viewerId);
    }

    public List<String> purposes(UUID viewerId) {
        return stack.purposes(viewerId);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restoreJournalled(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        forget(event.getEntity());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        forget(event.getPlayer());
    }

    /**
     * A journal left behind by a crash means the client may still be holding a sky nobody owns any
     * more, so the real sky is re-asserted and the journal cleared.
     */
    public void restoreJournalled(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        if (sections.read(viewerId, SECTION).isEmpty()) {
            return;
        }
        sections.write(viewerId, SECTION, Map.of());
        restoreReal(viewer);
    }

    /** Advances every running fade; a fade that reached its target stops sending. */
    void tickFades(int ticks) {
        for (Map.Entry<UUID, Fade> entry : fades.entrySet()) {
            Fade advanced = entry.getValue().advanced(ticks);
            Player viewer = Bukkit.getPlayer(advanced.player());
            if (viewer == null || !viewer.isOnline()) {
                fades.remove(entry.getKey());
                continue;
            }
            viewer.setPlayerTime(SkyFade.timeAt(advanced.from(), advanced.to(), advanced.fadeTicks(),
                advanced.elapsedTicks()), false);
            if (advanced.done()) {
                fades.remove(entry.getKey());
            } else {
                entry.setValue(advanced);
            }
        }
    }

    private void forget(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        if (!stack.owns(viewerId)) {
            return;
        }
        stack.forget(viewerId);
        fades.remove(viewerId);
        sections.write(viewerId, SECTION, Map.of());
        restoreReal(viewer);
    }

    private void show(Player viewer, SkyOverride override) {
        Runnable apply = () -> {
            if (override.time() != null) {
                startTime(viewer, override);
            }
            if (override.weather() != null) {
                viewer.setPlayerWeather(weather(override.weather()));
            }
            if (override.border() != null) {
                borders.apply(viewer, override.border());
            }
        };
        if (!FoliaScheduler.runEntity(plugin, viewer, apply, 0, null)) {
            apply.run();
        }
    }

    private void startTime(Player viewer, SkyOverride override) {
        long target = override.time();
        if (override.fadeTicks() <= 0) {
            fades.remove(viewer.getUniqueId());
            viewer.setPlayerTime(target, false);
            return;
        }
        long from = viewer.getWorld().getTime();
        fades.put(viewer.getUniqueId(), new Fade(viewer.getUniqueId(), from, target,
            override.fadeTicks(), 0));
        viewer.setPlayerTime(SkyFade.timeAt(from, target, override.fadeTicks(), 0), false);
    }

    private void restoreReal(Player viewer) {
        Runnable restore = () -> {
            viewer.resetPlayerTime();
            viewer.resetPlayerWeather();
            borders.restore(viewer);
        };
        if (!FoliaScheduler.runEntity(plugin, viewer, restore, 0, null)) {
            restore.run();
        }
    }

    private void journal(UUID viewerId) {
        Map<String, SkyOverride> active = stack.all(viewerId);
        if (active.isEmpty()) {
            sections.write(viewerId, SECTION, Map.of());
            return;
        }
        Map<String, Object> journal = new LinkedHashMap<>(active.size());
        for (Map.Entry<String, SkyOverride> entry : active.entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>(2);
            if (entry.getValue().time() != null) {
                values.put("time", entry.getValue().time());
            }
            if (entry.getValue().weather() != null) {
                values.put("weather", entry.getValue().weather());
            }
            journal.put(entry.getKey(), values);
        }
        sections.write(viewerId, SECTION, journal);
    }

    /** Bukkit has no per-player thunder, so anything wet is downfall. */
    private static WeatherType weather(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "rain", "downfall", "thunder", "storm" -> WeatherType.DOWNFALL;
            default -> WeatherType.CLEAR;
        };
    }
}
