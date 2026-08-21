package art.arcane.gloss;

import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.drop.RealDropSettingsDoc;

import java.util.List;
import java.util.Objects;

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
    RealDrops realDrops,
    Motd motd,
    Groups groups,
    Hotload hotload,
    Commands commands,
    boolean splashScreen,
    boolean metrics,
    Menus menus,
    Panels panels,
    Previews previews,
    EditorSync editorSync,
    Debug debug,
    CustomItems customItems,
    PlayerHeads playerHeads,
    Integration integration
) {
    private static final GlossConfig DEFAULTS = defaults();

    public record Holograms(
        boolean enabled,
        double stackDistance,
        int updateIntervalTicks,
        double viewRange,
        boolean perViewerPlaceholders,
        int temporaryUpdateIntervalTicks,
        boolean interpolatedMotion,
        int textArtMaxWidth,
        boolean highFrequencyAnimations,
        int maxAnimationFps,
        int animationPacketBudget
    ) {
    }

    public record Boards(
        boolean enabled,
        int updateIntervalTicks
    ) {
    }

    public record Tablist(
        boolean enabled,
        int updateIntervalTicks
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
        List<String> blacklistWorlds
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
        String nameFormat,
        String bundleFormat,
        int bundleEntryLimit,
        boolean bundleVerticalLabels,
        String bundleHeaderFormat,
        String bundleEntryFormat,
        String bundleMoreFormat,
        boolean preserveCustomNames,
        boolean useItemDisplayNames
    ) {
    }

    public record RealDrops(
        boolean enabled,
        Limits limits,
        Scale scale,
        Motion motion,
        Landing landing,
        Labels labels,
        Filters filters,
        Physics physics,
        Script script
    ) {
        public record Limits(
            int updateIntervalTicks,
            int settledPollIntervalTicks,
            int maxVisualsPerStack,
            int maxVisualsPerChunk,
            float viewRange,
            float spread
        ) {
        }

        public record Scale(float defaultScale, float flatItems, float thinBlocks) {
        }

        public record Motion(
            boolean tumble,
            float speedMultiplier,
            float degreesPerSecondX,
            float degreesPerSecondY,
            float degreesPerSecondZ,
            float variance,
            boolean changeOnBounce
        ) {
        }

        public record Landing(String mode, float tiltDegrees, boolean randomYaw, int transitionTicks) {
        }

        public record Labels(
            boolean enabled,
            float yOffset,
            float scale,
            float viewRange,
            String billboard,
            boolean seeThrough,
            boolean shadow,
            boolean background,
            int backgroundRed,
            int backgroundGreen,
            int backgroundBlue,
            int backgroundAlpha
        ) {
        }

        public record Filters(
            List<String> disabledWorlds,
            List<String> materialBlacklist,
            boolean onlyPlayerDrops
        ) {
        }

        public record Physics(
            boolean enabled,
            float gravityMultiplier,
            float bounce,
            float waterBuoyancy,
            float waterDrag
        ) {
        }

        public record Axis(String x, String y, String z) {
        }

        public record ScriptVar(String name, String expression) {
        }

        public record Script(
            boolean enabled,
            List<ScriptVar> vars,
            Axis offset,
            Axis rotation,
            Axis scale,
            String glow,
            String visible
        ) {
        }
    }

    public record Motd(
        boolean enabled
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

    public record Menus(
        boolean enabled,
        float uiScale
    ) {
    }

    public record Panels(
        boolean enabled
    ) {
    }

    public record Previews(
        boolean enabled,
        double lookDistance,
        float scale
    ) {
    }

    public record EditorSync(
        String builderUrl,
        boolean enabled,
        String endpoint,
        String createToken,
        int sessionMinutes,
        int pollSeconds,
        int maxProjectMiB
    ) {
    }

    public record Debug(
        boolean hitbox,
        boolean position,
        boolean animator
    ) {
    }

    public record CustomItems(
        boolean enabled,
        List<String> providers
    ) {
    }

    /**
     * Player-head icon resolution. {@code enabled} is a hard switch on outbound profile lookups:
     * with it off nothing leaves the server and every player-head icon draws
     * {@code unknownFallbackItem}.
     */
    public record PlayerHeads(
        boolean enabled,
        int cacheMinutes,
        int unknownCacheMinutes,
        int maxCachedProfiles,
        String unknownFallbackItem
    ) {
    }

    public record Integration(
        int sampleIntervalTicks
    ) {
    }

    public static GlossConfig from(GlossConfigFile file) {
        GlossConfigFile source = Objects.requireNonNull(file, "file");
        return new GlossConfig(
            new Holograms(
                source.features.holograms,
                source.holograms.stackDistance,
                source.holograms.updateIntervalTicks,
                source.holograms.viewRange,
                source.holograms.perViewerPlaceholders,
                source.holograms.temporaryUpdateIntervalTicks,
                source.holograms.interpolatedMotion,
                source.holograms.textArtMaxWidth,
                source.holograms.highFrequencyAnimations,
                source.holograms.maxAnimationFps,
                source.holograms.animationPacketBudget
            ),
            new Boards(
                source.features.boards,
                source.boards.updateIntervalTicks
            ),
            new Tablist(
                source.features.tablist,
                source.tablist.updateIntervalTicks
            ),
            new Emoji(
                source.features.emoji,
                source.emoji.emojiSpecificPermissions,
                source.emoji.tabComplete
            ),
            new Animations(
                source.features.animations
            ),
            new Chat(
                source.chat.color
            ),
            new Text(
                source.text.placeholders,
                source.text.functions
            ),
            new Bubbles(
                source.features.chatBubbles,
                List.copyOf(source.chatBubbles.blacklistWorlds)
            ),
            new Indicators(
                source.features.damageIndicators,
                source.damageIndicators.randomThrowForce,
                source.damageIndicators.initialUpForce,
                source.damageIndicators.gravityFactor,
                source.damageIndicators.maxPerSecond,
                source.damageIndicators.maxMsAlive,
                source.damageIndicators.damagePrefix,
                source.damageIndicators.healPrefix,
                source.damageIndicators.decimals,
                source.damageIndicators.showHeals
            ),
            new Drops(
                source.features.drops,
                source.drops.nameFormat,
                source.drops.bundleFormat,
                source.drops.bundleEntryLimit,
                source.drops.bundleVerticalLabels,
                source.drops.bundleHeaderFormat,
                source.drops.bundleEntryFormat,
                source.drops.bundleMoreFormat,
                source.drops.preserveCustomNames,
                source.drops.useItemDisplayNames
            ),
            RealDropSettingsDoc.DEFAULTS.toConfig(source.features.realDrops),
            new Motd(
                source.features.motd
            ),
            new Groups(
                source.groups.useVault
            ),
            new Hotload(
                source.hotload.watchIntervalTicks
            ),
            new Commands(
                source.commands.sounds
            ),
            source.splashScreen,
            source.metrics,
            new Menus(
                source.features.menus,
                (float) source.menus.uiScale
            ),
            new Panels(
                source.features.panels
            ),
            new Previews(
                source.features.previews,
                source.preview.lookDistance,
                (float) source.preview.scale
            ),
            new EditorSync(
                source.editor.builderUrl,
                source.editor.sync.enabled,
                source.editor.sync.endpoint,
                source.editor.sync.createToken,
                source.editor.sync.sessionMinutes,
                source.editor.sync.pollSeconds,
                source.editor.sync.maxProjectMiB
            ),
            new Debug(
                source.debug.hitbox,
                source.debug.position,
                source.debug.animator
            ),
            new CustomItems(
                source.items.customItems,
                List.copyOf(source.items.customItemProviders)
            ),
            new PlayerHeads(
                source.playerHeads.enabled,
                source.playerHeads.cacheMinutes,
                source.playerHeads.unknownCacheMinutes,
                source.playerHeads.maxCachedProfiles,
                source.playerHeads.unknownFallbackItem
            ),
            new Integration(
                source.integration.sampleIntervalTicks
            )
        );
    }

    public static GlossConfig current() {
        Gloss plugin = Gloss.instance;
        GlossConfig active = plugin == null ? null : plugin.cfg();
        return active == null ? DEFAULTS : active;
    }

    private static GlossConfig defaults() {
        GlossConfigFile file = new GlossConfigFile();
        file.normalize();
        return from(file);
    }
}
