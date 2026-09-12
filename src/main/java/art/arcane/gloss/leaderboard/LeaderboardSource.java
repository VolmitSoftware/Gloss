package art.arcane.gloss.leaderboard;

import art.arcane.volmlib.util.bukkit.Placeholders;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Where one leaderboard's number comes from. A source is resolved once at document load, so a
 * misspelt statistic refuses the document instead of failing silently every sample.
 */
@FunctionalInterface
public interface LeaderboardSource {
    /** @return the player's current value, or null when it cannot be read right now */
    Double sample(Player player);

    static LeaderboardSource of(LeaderboardDoc doc) {
        LeaderboardDoc.Source source = doc.source();
        return switch (source.type()) {
            case PAPI -> placeholder(source.placeholder());
            case STATISTIC -> statistic(source);
            case METRIC -> throw new IllegalArgumentException(
                "leaderboard metric source '" + source.key() + "' needs a per-player metric group, "
                    + "and no integrated plugin publishes one");
        };
    }

    private static LeaderboardSource placeholder(String placeholder) {
        String token = placeholder.startsWith("%") && placeholder.endsWith("%")
            ? placeholder : "%" + placeholder + "%";
        return player -> {
            String value = Placeholders.setPlaceholders(player, token);
            if (value.equals(token)) {
                return null;
            }
            try {
                return Double.valueOf(value.replace(",", ""));
            } catch (NumberFormatException notANumber) {
                return null;
            }
        };
    }

    private static LeaderboardSource statistic(LeaderboardDoc.Source source) {
        Statistic statistic = resolveStatistic(source.statistic());
        return switch (statistic.getType()) {
            case UNTYPED -> player -> (double) player.getStatistic(statistic);
            case ITEM, BLOCK -> {
                Material material = resolveMaterial(source, statistic);
                yield player -> (double) player.getStatistic(statistic, material);
            }
            case ENTITY -> {
                EntityType entity = resolveEntity(source, statistic);
                yield player -> (double) player.getStatistic(statistic, entity);
            }
        };
    }

    private static Statistic resolveStatistic(String name) {
        try {
            return Statistic.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown leaderboard statistic: " + name);
        }
    }

    private static Material resolveMaterial(LeaderboardDoc.Source source, Statistic statistic) {
        if (source.material().isEmpty()) {
            throw new IllegalArgumentException("statistic " + statistic.name() + " requires a material");
        }
        Material material = Material.matchMaterial(source.material());
        if (material == null) {
            throw new IllegalArgumentException("unknown leaderboard material: " + source.material());
        }
        return material;
    }

    private static EntityType resolveEntity(LeaderboardDoc.Source source, Statistic statistic) {
        if (source.entity().isEmpty()) {
            throw new IllegalArgumentException("statistic " + statistic.name() + " requires an entity");
        }
        try {
            return EntityType.valueOf(source.entity().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown leaderboard entity: " + source.entity());
        }
    }
}
