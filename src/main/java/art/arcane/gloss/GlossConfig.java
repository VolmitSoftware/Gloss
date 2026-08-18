package art.arcane.gloss;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public record GlossConfig(
    Holograms holograms,
    Boards boards,
    Tablist tablist,
    Emoji emoji,
    Animations animations,
    Chat chat,
    Text text,
    Bubbles bubbles,
    Indicators indicators,
    Drops drops,
    Motd motd,
    Groups groups,
    Hotload hotload,
    Commands commands,
    boolean splashScreen,
    boolean metrics
) {
    public record Holograms(
        boolean enabled,
        double stackDistance,
        int updateIntervalTicks,
        double viewRange,
        boolean perViewerPlaceholders,
        int temporaryUpdateIntervalTicks,
        int textArtMaxWidth
    ) {
    }

    public record Boards(
        boolean enabled,
        int updateIntervalTicks
    ) {
    }

    public record Tablist(
        boolean enabled,
        boolean useHeaderFooters,
        String header,
        String footer,
        int updateIntervalTicks,
        boolean groupListNames
    ) {
    }

    public record Emoji(
        boolean enabled,
        boolean emojiSpecificPermissions,
        boolean tabComplete
    ) {
    }

    public record Animations(
        boolean enabled
    ) {
    }

    public record Chat(
        boolean colorEnabled
    ) {
    }

    public record Text(
        boolean placeholders,
        boolean functions
    ) {
    }

    public record Bubbles(
        boolean enabled,
        boolean followPlayers,
        boolean hideOwn,
        int wordWrapChars,
        long maxTimeAliveMs,
        String prefix,
        double offsetX,
        double offsetY,
        double offsetZ,
        List<String> blacklistWorlds,
        int lineStaggerTicks,
        boolean flyAway
    ) {
    }

    public record Indicators(
        boolean enabled,
        double randomThrowForce,
        double initialUpForce,
        double gravityForce,
        int maxPerSecond,
        long maxMsAlive,
        String damagePrefix,
        String healPrefix,
        int decimals,
        boolean showHeals
    ) {
    }

    public record Drops(
        boolean enabled,
        String nameFormat
    ) {
    }

    public record Motd(
        boolean enabled,
        List<String> texts
    ) {
    }

    public record Groups(
        boolean useVault
    ) {
    }

    public record Hotload(
        int watchIntervalTicks
    ) {
    }

    public record Commands(
        boolean sounds
    ) {
    }

    public static GlossConfig load(FileConfiguration source) {
        ConfigurationSection features = section(source, "features");
        ConfigurationSection holograms = section(source, "holograms");
        ConfigurationSection boards = section(source, "boards");
        ConfigurationSection tablist = section(source, "tablist");
        ConfigurationSection emoji = section(source, "emoji");
        ConfigurationSection chat = section(source, "chat");
        ConfigurationSection text = section(source, "text");
        ConfigurationSection bubbles = section(source, "chat-bubbles");
        ConfigurationSection indicators = section(source, "damage-indicators");
        ConfigurationSection drops = section(source, "drops");
        ConfigurationSection motd = section(source, "motd");
        ConfigurationSection groups = section(source, "groups");
        ConfigurationSection hotload = section(source, "hotload");
        ConfigurationSection commands = section(source, "commands");

        return new GlossConfig(
            new Holograms(
                features.getBoolean("holograms", true),
                clamp(holograms.getDouble("stack-distance", 0.26D), 0.05D, 2.0D),
                clampInt(holograms.getInt("update-interval-ticks", 10), 1, 200),
                clamp(holograms.getDouble("view-range", 48.0D), 4.0D, 128.0D),
                holograms.getBoolean("per-viewer-placeholders", true),
                clampInt(holograms.getInt("temporary-update-interval-ticks", 2), 1, 20),
                clampInt(holograms.getInt("text-art-max-width", 48), 8, 128)
            ),
            new Boards(
                features.getBoolean("boards", true),
                clampInt(boards.getInt("update-interval-ticks", 20), 1, 200)
            ),
            new Tablist(
                features.getBoolean("tablist", true),
                tablist.getBoolean("use-header-footers", true),
                tablist.getString("header", "&d&lGloss"),
                tablist.getString("footer", "&7VolmitSoftware.com"),
                clampInt(tablist.getInt("update-interval-ticks", 40), 1, 400),
                tablist.getBoolean("group-list-names", true)
            ),
            new Emoji(
                features.getBoolean("emoji", true),
                emoji.getBoolean("emoji-specific-permissions", false),
                emoji.getBoolean("tab-complete", true)
            ),
            new Animations(
                features.getBoolean("animations", true)
            ),
            new Chat(
                chat.getBoolean("color", true)
            ),
            new Text(
                text.getBoolean("placeholders", true),
                text.getBoolean("functions", true)
            ),
            new Bubbles(
                features.getBoolean("chat-bubbles", true),
                bubbles.getBoolean("follow-players", true),
                bubbles.getBoolean("hide-own-messages", true),
                clampInt(bubbles.getInt("word-wrap-break-chars", 32), 8, 128),
                clampLong(bubbles.getLong("max-time-alive", 5000L), 500L, 60000L),
                bubbles.getString("message.prefix", "&7"),
                bubbles.getDouble("message.offset.x", 0.0D),
                bubbles.getDouble("message.offset.y", 1.0D),
                bubbles.getDouble("message.offset.z", 0.0D),
                List.copyOf(bubbles.getStringList("blacklist-worlds")),
                clampInt(bubbles.getInt("line-stagger-ticks", 5), 0, 40),
                bubbles.getBoolean("fly-away", true)
            ),
            new Indicators(
                features.getBoolean("damage-indicators", true),
                clamp(indicators.getDouble("motion.random-throw-force", 0.08D), 0.0D, 2.0D),
                clamp(indicators.getDouble("motion.initial-up-force", 0.13D), 0.0D, 2.0D),
                clamp(indicators.getDouble("motion.gravity-factor", 0.0093D), 0.0D, 1.0D),
                clampInt(indicators.getInt("max-indicators-per-second", 40), 1, 1000),
                clampLong(indicators.getLong("max-ms-alive", 3000L), 250L, 30000L),
                indicators.getString("damage-indicator-prefix", "&c&l"),
                indicators.getString("heal-indicator-prefix", "&a&l"),
                clampInt(indicators.getInt("decimals", 0), 0, 2),
                indicators.getBoolean("show-heals", true)
            ),
            new Drops(
                features.getBoolean("drops", true),
                drops.getString("name-format", "&7{count}x {type}")
            ),
            new Motd(
                motd.getBoolean("enabled", false),
                List.copyOf(motd.getStringList("texts"))
            ),
            new Groups(
                groups.getBoolean("use-vault", true)
            ),
            new Hotload(
                clampInt(hotload.getInt("watch-interval-ticks", 20), 1, 200)
            ),
            new Commands(
                commands.getBoolean("sounds", true)
            ),
            source.getBoolean("splash-screen", true),
            source.getBoolean("metrics", true)
        );
    }

    private static ConfigurationSection section(FileConfiguration source, String path) {
        ConfigurationSection existing = source.getConfigurationSection(path);
        return existing != null ? existing : source.createSection(path);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clampLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
