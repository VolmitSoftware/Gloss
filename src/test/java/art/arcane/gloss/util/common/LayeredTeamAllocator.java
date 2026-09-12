package art.arcane.gloss.util.common;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A team allocator double that models what a real client does: one team per (viewer, entry), with
 * every purpose contributing a layer to it. A double that handed each purpose its own team would
 * describe a world the client does not have, where three Gloss features can claim the same entry
 * and all three survive; on a real client only the last claim is in effect. Suites assert against
 * {@link #layersFor} so a purpose that quietly cancels another one fails here.
 */
public final class LayeredTeamAllocator implements TeamAllocator {
    public final List<String> calls = new ArrayList<>();
    public final List<TeamStyle> styles = new ArrayList<>();

    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<String, Map<String, TeamStyle>> teams = new LinkedHashMap<>();
    private final Map<String, String> teamNames = new LinkedHashMap<>();
    private int sequence;

    @Override
    public TeamHandle claim(Player viewer, String purpose, String entry, TeamStyle style) {
        names.put(viewer.getUniqueId(), viewer.getName());
        String key = key(viewer.getUniqueId(), entry);
        Map<String, TeamStyle> layers = teams.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        String team = teamName(key);
        calls.add("claim:" + viewer.getName() + "/" + purpose + "/" + entry);
        styles.add(style);
        layers.put(purpose, style);
        return new TeamHandle(viewer.getUniqueId(), purpose, entry, team);
    }

    @Override
    public void update(TeamHandle handle, TeamStyle style) {
        Map<String, TeamStyle> layers = teams.get(key(handle.viewerId(), handle.entry()));
        if (layers == null || !layers.containsKey(handle.purpose())) {
            return;
        }
        calls.add("update:" + name(handle.viewerId()) + "/" + handle.purpose() + "/" + handle.entry());
        styles.add(style);
        layers.put(handle.purpose(), style);
    }

    @Override
    public void release(TeamHandle handle) {
        String key = key(handle.viewerId(), handle.entry());
        Map<String, TeamStyle> layers = teams.get(key);
        if (layers == null || layers.remove(handle.purpose()) == null) {
            return;
        }
        calls.add("release:" + name(handle.viewerId()) + "/" + handle.purpose() + "/" + handle.entry());
        if (layers.isEmpty()) {
            teams.remove(key);
        }
    }

    @Override
    public void releaseAll(Player viewer, String purpose) {
        calls.add("releaseAll:" + viewer.getName() + "/" + purpose);
        for (Map.Entry<String, Map<String, TeamStyle>> entry : List.copyOf(teams.entrySet())) {
            if (!entry.getKey().startsWith(viewer.getUniqueId() + "/")) {
                continue;
            }
            entry.getValue().remove(purpose);
            if (entry.getValue().isEmpty()) {
                teams.remove(entry.getKey());
            }
        }
    }

    @Override
    public void forget(UUID viewerId) {
        calls.add("forget:" + viewerId);
        teams.keySet().removeIf(key -> key.startsWith(viewerId + "/"));
    }

    /** The layers the one team for this viewer-entry is composed from, keyed by purpose. */
    public Map<String, TeamStyle> layersFor(UUID viewerId, String entry) {
        Map<String, TeamStyle> layers = teams.get(key(viewerId, entry));
        return layers == null ? Map.of() : Map.copyOf(layers);
    }

    public String teamFor(UUID viewerId, String entry) {
        return teamNames.get(key(viewerId, entry));
    }

    private String teamName(String key) {
        return teamNames.computeIfAbsent(key, ignored -> "team" + ++sequence);
    }

    private String name(UUID viewerId) {
        return names.getOrDefault(viewerId, viewerId.toString());
    }

    private static String key(UUID viewerId, String entry) {
        return viewerId + "/" + entry;
    }
}
