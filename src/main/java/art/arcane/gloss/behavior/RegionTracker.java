package art.arcane.gloss.behavior;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Polls each player's WorldGuard region set on their region thread and fires
 * {@code region_leave} then {@code region_enter} for the difference; the first poll enters
 * everything the player already stands in.
 */
public final class RegionTracker {
    private final Function<Location, Set<String>> regionsAt;
    private final BiConsumer<BehaviorTrigger, TriggerEvent> sink;
    private final Map<UUID, Set<String>> last = new ConcurrentHashMap<>();

    public RegionTracker(Function<Location, Set<String>> regionsAt, BiConsumer<BehaviorTrigger, TriggerEvent> sink) {
        this.regionsAt = regionsAt;
        this.sink = sink;
    }

    public void tick(Player player) {
        Set<String> now = new HashSet<>(regionsAt.apply(player.getLocation()));
        Set<String> before = last.put(player.getUniqueId(), now);
        if (before == null) {
            before = Set.of();
        }
        for (String region : before) {
            if (!now.contains(region)) {
                sink.accept(BehaviorTrigger.REGION_LEAVE, TriggerEvent.region(player, region));
            }
        }
        for (String region : now) {
            if (!before.contains(region)) {
                sink.accept(BehaviorTrigger.REGION_ENTER, TriggerEvent.region(player, region));
            }
        }
    }

    public void forget(UUID player) {
        last.remove(player);
    }

    public void clear() {
        last.clear();
    }
}
