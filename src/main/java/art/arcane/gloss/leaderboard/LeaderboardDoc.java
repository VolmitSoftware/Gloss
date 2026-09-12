package art.arcane.gloss.leaderboard;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import com.google.gson.annotations.SerializedName;

/**
 * A ranked top-N list Gloss computes itself: where the number comes from, how many entries are
 * kept, which way they sort, and when the period bucket rolls over. All-time totals are always
 * kept alongside the period, so a reset never destroys history.
 */
public record LeaderboardDoc(int schemaVersion, long revision, Source source, Integer size, Order order,
                             Reset reset, Boolean includeOffline, Format format, Sample sample) {
    public static final String KIND = "leaderboards";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 1000;
    public static final int DEFAULT_PLAYERS_PER_TICK = 8;
    public static final int MAX_PLAYERS_PER_TICK = 64;

    public LeaderboardDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        if (source == null) {
            throw new IllegalArgumentException("leaderboard documents require a source block");
        }
        size = size == null ? DEFAULT_SIZE : Math.clamp(size.intValue(), 1, MAX_SIZE);
        order = order == null ? Order.DESC : order;
        reset = reset == null ? Reset.NEVER : reset;
        includeOffline = includeOffline == null || includeOffline;
        format = format == null ? Format.DEFAULTS : format;
        sample = sample == null ? Sample.DEFAULTS : sample;
    }

    public static LeaderboardDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, LeaderboardDoc.class);
    }

    public enum SourceType {
        @SerializedName(value = "papi", alternate = {"PAPI", "placeholder"})
        PAPI,
        @SerializedName(value = "statistic", alternate = {"STATISTIC"})
        STATISTIC,
        @SerializedName(value = "metric", alternate = {"METRIC"})
        METRIC
    }

    public enum Order {
        @SerializedName(value = "desc", alternate = {"DESC", "descending"})
        DESC,
        @SerializedName(value = "asc", alternate = {"ASC", "ascending"})
        ASC
    }

    public enum Reset {
        @SerializedName(value = "never", alternate = {"NEVER"})
        NEVER,
        @SerializedName(value = "hourly", alternate = {"HOURLY"})
        HOURLY,
        @SerializedName(value = "daily", alternate = {"DAILY"})
        DAILY,
        @SerializedName(value = "weekly", alternate = {"WEEKLY"})
        WEEKLY,
        @SerializedName(value = "monthly", alternate = {"MONTHLY"})
        MONTHLY
    }

    /**
     * @param placeholder the PlaceholderAPI token for a {@code papi} source
     * @param statistic   the Bukkit {@code Statistic} name for a {@code statistic} source
     * @param material    the block or item a statistic is keyed by, when it needs one
     * @param entity      the entity type a statistic is keyed by, when it needs one
     * @param key         the per-player metric key for a {@code metric} source
     */
    public record Source(SourceType type, String placeholder, String statistic, String material,
                         String entity, String key) {
        public Source {
            if (type == null) {
                throw new IllegalArgumentException("leaderboard source type must be papi, statistic or metric");
            }
            placeholder = trim(placeholder);
            statistic = trim(statistic);
            material = trim(material);
            entity = trim(entity);
            key = trim(key);
            switch (type) {
                case PAPI -> require(placeholder, "a papi source requires a placeholder");
                case STATISTIC -> require(statistic, "a statistic source requires a statistic");
                case METRIC -> require(key, "a metric source requires a key");
            }
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }

        private static void require(String value, String message) {
            if (value.isEmpty()) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    public record Format(String value, String name) {
        public static final Format DEFAULTS = new Format(null, null);

        public Format {
            value = value == null || value.isBlank() ? "{{ value }}" : value;
            name = name == null || name.isBlank() ? "{{ name }}" : name;
        }
    }

    public record Sample(Integer playersPerTick) {
        public static final Sample DEFAULTS = new Sample(null);

        public Sample {
            playersPerTick = playersPerTick == null ? DEFAULT_PLAYERS_PER_TICK
                : Math.clamp(playersPerTick.intValue(), 1, MAX_PLAYERS_PER_TICK);
        }
    }
}
