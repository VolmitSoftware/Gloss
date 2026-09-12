package art.arcane.gloss.sky;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Who owns a viewer's sky. Several features may claim it at once, each under its own purpose; the
 * newest claim is what the viewer sees, and releasing it hands the sky back to the one underneath
 * rather than to the real world.
 */
public final class SkyOwnershipStack {
    private final ConcurrentMap<UUID, LinkedHashMap<String, SkyOverride>> owners = new ConcurrentHashMap<>();

    public void push(UUID player, SkyOverride override) {
        LinkedHashMap<String, SkyOverride> stack = owners.computeIfAbsent(player,
            ignored -> new LinkedHashMap<>());
        synchronized (stack) {
            stack.remove(override.purpose());
            stack.put(override.purpose(), override);
        }
    }

    public void release(UUID player, String purpose) {
        LinkedHashMap<String, SkyOverride> stack = owners.get(player);
        if (stack == null) {
            return;
        }
        synchronized (stack) {
            stack.remove(purpose);
            if (stack.isEmpty()) {
                owners.remove(player, stack);
            }
        }
    }

    public Optional<SkyOverride> top(UUID player) {
        LinkedHashMap<String, SkyOverride> stack = owners.get(player);
        if (stack == null) {
            return Optional.empty();
        }
        synchronized (stack) {
            SkyOverride newest = null;
            for (SkyOverride override : stack.values()) {
                newest = override;
            }
            return Optional.ofNullable(newest);
        }
    }

    public boolean owns(UUID player) {
        return top(player).isPresent();
    }

    /** Every purpose this player carries, newest first. */
    public List<String> purposes(UUID player) {
        LinkedHashMap<String, SkyOverride> stack = owners.get(player);
        if (stack == null) {
            return List.of();
        }
        synchronized (stack) {
            List<String> purposes = new ArrayList<>(stack.keySet());
            java.util.Collections.reverse(purposes);
            return List.copyOf(purposes);
        }
    }

    public Map<String, SkyOverride> all(UUID player) {
        LinkedHashMap<String, SkyOverride> stack = owners.get(player);
        if (stack == null) {
            return Map.of();
        }
        synchronized (stack) {
            return Map.copyOf(stack);
        }
    }

    public List<UUID> players() {
        return List.copyOf(owners.keySet());
    }

    public void forget(UUID player) {
        owners.remove(player);
    }

    public void clear() {
        owners.clear();
    }
}
