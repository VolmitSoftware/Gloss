package art.arcane.gloss;

import art.arcane.gloss.animation.clip.Blend;
import art.arcane.gloss.animation.clip.Clip;
import art.arcane.gloss.animation.clip.ClipSet;
import art.arcane.gloss.animation.clip.Easing;
import art.arcane.gloss.animation.clip.Keyframe;
import art.arcane.gloss.animation.clip.LoopMode;
import art.arcane.gloss.animation.clip.Profile;
import art.arcane.gloss.animation.clip.Target;
import art.arcane.gloss.animation.clip.Track;
import art.arcane.gloss.animation.clip.Trigger;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.hologram.DisplayRefresh;
import art.arcane.gloss.service.BudgetedVisibilityGovernor;
import art.arcane.gloss.util.common.PacketTeamAllocator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public record GlossConfig(
    String language,
    boolean metrics,
    boolean splashScreen,
    Holograms holograms,
    Particles particles,
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
    Menus menus,
    Panels panels,
    Previews previews,
    EditorSync editorSync,
    Debug debug,
    CustomItems customItems,
    PlayerHeads playerHeads,
    Integration integration,
    Modules modules,
    BudgetedVisibilityGovernor.Limits visibility,
    Images images,
    PacketTeamAllocator.Policy teams,
    Imports imports
) {
    private static final GlossConfig DEFAULTS = defaults();

    public GlossConfig withLanguage(String locale) {
        return new GlossConfig(locale, metrics, splashScreen, holograms, particles, boards, tablist,
            emoji, animations, chat, text, bubbles, indicators, drops, realDrops, motd, groups,
            hotload, commands, menus, panels, previews, editorSync, debug, customItems, playerHeads, integration,
            modules, visibility, images, teams, imports);
    }

    /**
     * Headline module snapshots. Each lane owns one nested record here plus its block in
     * {@link #from}; the master switch is the record's {@code enabled}, sourced from {@code features.*}.
     */
    public record Imports(int maxFileBytes, int maxPreviewBytes, int maxFiles, int previewLifetimeSeconds,
                          int maxPreparedPreviews, int maxCachedBytes, int maxVisitedEntries,
                          int maxDirectoryDepth, long maxPreparationBytes, long maxPreparationMillis) {
        public Imports(int maxFileBytes, int maxPreviewBytes, int maxFiles, int previewLifetimeSeconds,
                       int maxPreparedPreviews, int maxCachedBytes) {
            this(maxFileBytes, maxPreviewBytes, maxFiles, previewLifetimeSeconds, maxPreparedPreviews,
                maxCachedBytes, 65536, 16);
        }

        public Imports(int maxFileBytes, int maxPreviewBytes, int maxFiles, int previewLifetimeSeconds,
                       int maxPreparedPreviews, int maxCachedBytes, int maxVisitedEntries, int maxDirectoryDepth) {
            this(maxFileBytes, maxPreviewBytes, maxFiles, previewLifetimeSeconds, maxPreparedPreviews,
                maxCachedBytes, maxVisitedEntries, maxDirectoryDepth, 1073741824L, 30000L);
        }

        public Imports {
            if (maxPreparationBytes < 1024L || maxPreparationBytes > 17179869184L
                || maxPreparationMillis < 1L || maxPreparationMillis > 600000L
                || maxFileBytes < 1 || maxPreviewBytes < maxFileBytes || maxFiles < 1
                || previewLifetimeSeconds < 1 || previewLifetimeSeconds > 86400 || maxPreparedPreviews < 1
                || maxCachedBytes < maxPreviewBytes || maxVisitedEntries < 1 || maxVisitedEntries > 1048576
                || maxDirectoryDepth < 1 || maxDirectoryDepth > 128) {
                throw new IllegalArgumentException("Import limits must be positive and fit the preview capacity");
            }
        }
    }

    public record Images(int maxFileBytes, int maxPixels, int maxDimension, int rasterMaxDimension,
                         int cacheBytes, int maxEntries, int maxPending, int workerThreads) {
    }

    public record Modules(
        // --- lane:screen ---
        Surfaces surfaces,
        Nametags nametags,
        // --- lane:chat ---
        Leaderboards leaderboards,
        Channels channels,
        Strings strings,
        // --- lane:forms ---
        Inventories inventories,
        // --- lane:world ---
        Markers markers,
        Waypoints waypoints,
        Camera camera,
        Sky sky,
        Nameplates nameplates,
        Glow glow,
        // --- lane:behaviors ---
        Behaviors behaviors,
        // --- lane:authoring ---
        GlossPacks glosspacks,
        History history,
        // --- lane:forge ---
        Forge forge,
        // --- lane:connections ---
        Connections connections,
        // --- lane:fixes ---
        Bedrock bedrock
    ) {
    }

    public record Surfaces(boolean enabled, int refreshIntervalTicks, int maxBossBarsPerViewer, int titleQueueLimit) {
    }

    public record Nametags(boolean enabled, int refreshIntervalTicks, int snapshotReadLimit,
                           double viewerRange, int maxSubjectsPerViewer) {
    }

    public record Leaderboards(boolean enabled, int sampleIntervalTicks, int maxEntries) {
    }

    public record Channels(boolean enabled) {
    }

    public record Strings(boolean enabled) {
    }

    public record Inventories(boolean enabled, boolean closeOnTeleport, String unsupportedIconItem) {
    }

    public record Markers(boolean enabled, int maxPerViewer, double viewRange,
                          int anchorSnapshotTicks, int anchorMaxAgeTicks, int anchorCacheEntries) {
        public Markers(boolean enabled, int maxPerViewer, double viewRange) {
            this(enabled, maxPerViewer, viewRange, 2, 100, 4096);
        }
    }

    public record Waypoints(boolean enabled, int maxPerViewer, int refreshTicks,
                            double positionThreshold, double azimuthThreshold) {
        public Waypoints(boolean enabled, int maxPerViewer) {
            this(enabled, maxPerViewer, 20, 1.0D, 0.017D);
        }
    }

    public record Camera(boolean enabled, int maxRideSeconds) {
    }

    public record Sky(boolean enabled, int fadeIntervalTicks, int maxPendingPerViewer, int maxPendingOperations) {
    }

    public record Nameplates(boolean enabled, double viewerRange, int maxSubjectsPerViewer, int refreshIntervalTicks) {
    }

    public record Glow(boolean enabled, int sweepIntervalTicks, double viewerRange, int maxTargetsPerViewer) {
    }

    public record Behaviors(boolean enabled, int maxActionsPerTick, int maxTimersPerPlayer, int stateFlushSeconds,
                            int chatMaxWorkUnits, int maxTimersGlobal, int maxTimersWithoutPlayer) {
        public Behaviors(boolean enabled, int maxActionsPerTick, int maxTimersPerPlayer, int stateFlushSeconds,
                         int chatMaxWorkUnits) {
            this(enabled, maxActionsPerTick, maxTimersPerPlayer, stateFlushSeconds, chatMaxWorkUnits, 8192, 256);
        }
    }

    public record GlossPacks(boolean enabled, boolean allowServerCommands) {
    }

    public record History(boolean enabled, int maxVersions, int maxAgeDays,
                          int maxTransactionBackups, long maxTransactionBackupBytes) {
        public History(boolean enabled, int maxVersions, int maxAgeDays) {
            this(enabled, maxVersions, maxAgeDays, 20, 1073741824L);
        }
    }

    public record Forge(boolean enabled, String url, boolean serve, String serveBind, int servePort, boolean required,
                        String prompt, int packFormat, int codepointBase, int listenerThreads, int listenerBacklog,
                        int buildDebounceTicks, int buildQueueCapacity, PackLimits limits) {
        public Forge(boolean enabled, String url, boolean serve, String serveBind, int servePort, boolean required,
                     String prompt, int packFormat, int codepointBase, int listenerThreads, int listenerBacklog,
                     int buildDebounceTicks, int buildQueueCapacity) {
            this(enabled, url, serve, serveBind, servePort, required, prompt, packFormat, codepointBase,
                listenerThreads, listenerBacklog, buildDebounceTicks, buildQueueCapacity, PackLimits.DEFAULT);
        }
    }

    public record PackLimits(int maxBuildFiles, long maxBuildBytes, long maxBuildPixels,
                             int maxRetainedArtifacts, long maxRetainedBytes, long artifactRetentionSeconds) {
        public static final PackLimits DEFAULT = new PackLimits(8192, 67108864L, 67108864L, 8, 536870912L, 600);

        public PackLimits {
            if (maxBuildFiles < 1 || maxBuildBytes < 1 || maxBuildPixels < 1 || maxRetainedArtifacts < 1
                || maxRetainedBytes < 1 || artifactRetentionSeconds < 0 || artifactRetentionSeconds > 2592000) {
                throw new IllegalArgumentException("Invalid resource pack limits");
            }
        }
    }

    public record Connections(boolean enabled) {
    }

    public record Bedrock(String detection, boolean hideHolograms, boolean hidePanels, boolean hideBubbles,
                          boolean hideIndicators, boolean hideDrops, boolean hideOverlays) {
    }


    public record Holograms(
        boolean enabled,
        double viewRange,
        boolean perViewerPlaceholders,
        int temporaryUpdateIntervalTicks,
        boolean interpolatedMotion,
        boolean highFrequencyAnimations,
        int maxAnimationFps,
        int animationPacketBudget
    ) {
    }

    public record Particles(
        boolean enabled,
        int samplesPerViewerPerTick,
        int samplesPerTick,
        int maxCachedSamplesPerLayer
    ) {
    }

    public record Boards(
        boolean enabled,
        int updateIntervalTicks
    ) {
    }

    public record Tablist(
        boolean enabled,
        int updateIntervalTicks,
        int snapshotReadLimit
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

    public record Bubbles(boolean enabled, int maxActive) {
        public Bubbles(boolean enabled) {
            this(enabled, 2048);
        }
    }

    public record Indicators(boolean enabled, int maxActive) {
        public Indicators(boolean enabled) {
            this(enabled, 2048);
        }
    }

    public record Drops(boolean enabled) {
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
        Script script,
        RealDropAnimation animation,
        List<ParticleLayer> particleLayers
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
            boolean changeOnBounce,
            float velocityInfluence,
            float submergedSpinMultiplier,
            float groundRollMultiplier
        ) {
        }

        public record Landing(
            String mode,
            float tiltDegrees,
            boolean randomYaw,
            int transitionTicks,
            float faceAttraction,
            float movingFaceAttraction,
            float alignmentDegrees,
            int settleDelayTicks
        ) {
        }

        public record Labels(
            boolean enabled,
            float yOffset,
            String format,
            boolean useItemDisplayNames,
            Map<String, String> names,
            LabelBundle bundle,
            IconDisplayStyle style,
            HologramBox box,
            ShowCondition show,
            boolean preserveCustomNames,
            DisplayRefresh refresh
        ) {
            public Labels(boolean enabled, float yOffset, String format, boolean useItemDisplayNames,
                          Map<String, String> names, LabelBundle bundle, IconDisplayStyle style,
                          HologramBox box, ShowCondition show, boolean preserveCustomNames) {
                this(enabled, yOffset, format, useItemDisplayNames, names, bundle, style, box, show,
                    preserveCustomNames, DisplayRefresh.DEFAULTS);
            }
        }

        public record LabelBundle(
            String format,
            int entryLimit,
            boolean vertical,
            String headerFormat,
            String entryFormat,
            String moreFormat
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

        public enum AnimationTrigger {
            SPAWN,
            AIRBORNE,
            REBOUNDING,
            ROLLING,
            SLIDING,
            SETTLING,
            SETTLED,
            SUBMERGED,
            FLOATING,
            IMPACT,
            BOUNCE,
            ENTER_FLUID,
            EXIT_FLUID,
            START_ROLL,
            SETTLE,
            WAKE;

            private final Trigger clip = Trigger.of(name());

            public Trigger toClip() {
                return clip;
            }
        }

        public enum AnimationTarget {
            OFFSET_X,
            OFFSET_Y,
            OFFSET_Z,
            ROTATION_X,
            ROTATION_Y,
            ROTATION_Z,
            SCALE_X,
            SCALE_Y,
            SCALE_Z,
            GLOW,
            VISIBLE,
            PHYSICS,
            LIGHT_LEVEL;

            public Target toClip() {
                return Target.valueOf(name());
            }
        }

        public enum AnimationBlend {
            ADD,
            REPLACE,
            MULTIPLY;

            public Blend toClip() {
                return Blend.valueOf(name());
            }
        }

        public enum AnimationEasing {
            LINEAR,
            HOLD,
            EASE_IN,
            EASE_OUT,
            EASE_IN_OUT,
            BACK_OUT;

            public Easing.Curve toClip() {
                return Easing.Curve.of(Easing.valueOf(name()));
            }
        }

        public record MaterialProperties(double glow, double lightLevel) {
            public art.arcane.gloss.animation.clip.MaterialProperties toClip() {
                return new art.arcane.gloss.animation.clip.MaterialProperties(glow, lightLevel);
            }
        }

        public record AnimationKeyframe(
            double tick,
            double value,
            String materialMap,
            AnimationEasing easing
        ) {
            public Keyframe toClip() {
                return new Keyframe(tick, value, materialMap, easing == null ? null : easing.toClip());
            }
        }

        public record AnimationTrack(
            AnimationTarget target,
            AnimationBlend blend,
            List<AnimationKeyframe> keyframes
        ) {
            public Track toClip() {
                return new Track(null, target == null ? null : target.toClip(), blend == null ? null : blend.toClip(),
                    convertEach(keyframes, AnimationKeyframe::toClip));
            }
        }

        public record AnimationClip(
            AnimationTrigger trigger,
            double durationTicks,
            boolean loop,
            List<AnimationTrack> tracks
        ) {
            public Clip toClip() {
                return new Clip(trigger == null ? null : trigger.toClip(), durationTicks,
                    loop ? LoopMode.LOOP : LoopMode.ONCE, convertEach(tracks, AnimationTrack::toClip));
            }
        }

        public record AnimationProfile(
            String id,
            int priority,
            List<String> materials,
            List<AnimationClip> clips
        ) {
            public Profile toClip() {
                return new Profile(id, priority, materials, convertEach(clips, AnimationClip::toClip));
            }
        }

        public record RealDropAnimation(
            boolean enabled,
            Map<String, Map<String, MaterialProperties>> materialProperties,
            List<AnimationProfile> profiles
        ) {
            public ClipSet toClipSet() {
                Map<String, Map<String, art.arcane.gloss.animation.clip.MaterialProperties>> converted = null;
                if (materialProperties != null) {
                    converted = new LinkedHashMap<>(materialProperties.size());
                    for (Map.Entry<String, Map<String, MaterialProperties>> group : materialProperties.entrySet()) {
                        converted.put(group.getKey(), group.getValue() == null ? null
                            : convertValues(group.getValue()));
                    }
                }
                return new ClipSet(enabled, converted, convertEach(profiles, AnimationProfile::toClip));
            }

            private static Map<String, art.arcane.gloss.animation.clip.MaterialProperties> convertValues(
                Map<String, MaterialProperties> source
            ) {
                Map<String, art.arcane.gloss.animation.clip.MaterialProperties> values =
                    new LinkedHashMap<>(source.size());
                for (Map.Entry<String, MaterialProperties> entry : source.entrySet()) {
                    values.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().toClip());
                }
                return values;
            }
        }

        private static <S, T> List<T> convertEach(List<S> source, Function<S, T> mapper) {
            if (source == null) {
                return null;
            }
            List<T> converted = new ArrayList<>(source.size());
            for (S element : source) {
                converted.add(element == null ? null : mapper.apply(element));
            }
            return Collections.unmodifiableList(converted);
        }
    }

    public record Motd(
        boolean enabled,
        int snapshotRefreshTicks
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
        float uiScale,
        int maxListEntries
    ) {
    }

    public record Panels(boolean enabled, int visibilityIntervalTicks, int followIntervalTicks,
                         int permissionCacheTicks) {
        public Panels(boolean enabled) {
            this(enabled, 1, 1, 20);
        }
    }

    public record Previews(boolean enabled) {
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
        int sampleIntervalTicks,
        int maxSampleAgeMs,
        int retainUnavailableMs,
        int errorRetryTicks,
        String unavailableText,
        int maxReferencedMetrics,
        long referenceWindowMs
    ) {
        public Integration(int sampleIntervalTicks, int maxSampleAgeMs, int retainUnavailableMs,
                           int errorRetryTicks, String unavailableText) {
            this(sampleIntervalTicks, maxSampleAgeMs, retainUnavailableMs, errorRetryTicks, unavailableText, 256, 60000L);
        }
    }

    public static GlossConfig from(GlossConfigFile file) {
        GlossConfigFile source = Objects.requireNonNull(file, "file");
        return new GlossConfig(
            source.language,
            source.metrics,
            source.splashScreen,
            new Holograms(
                source.features.holograms,
                source.holograms.viewRange,
                source.holograms.perViewerPlaceholders,
                source.holograms.temporaryUpdateIntervalTicks,
                source.holograms.interpolatedMotion,
                source.holograms.highFrequencyAnimations,
                source.holograms.maxAnimationFps,
                source.holograms.animationPacketBudget
            ),
            new Particles(
                source.features.particles,
                source.particles.samplesPerViewerPerTick,
                source.particles.samplesPerTick,
                source.particles.maxCachedSamplesPerLayer
            ),
            new Boards(
                source.features.boards,
                source.boards.updateIntervalTicks
            ),
            new Tablist(
                source.features.tablist,
                source.tablist.updateIntervalTicks,
                source.tablist.snapshotReadLimit
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
                source.features.chatBubbles, source.temporaryDisplays.maxActiveBubbles
            ),
            new Indicators(
                source.features.damageIndicators, source.temporaryDisplays.maxActiveIndicators
            ),
            new Drops(
                source.features.drops
            ),
            RealDropSettingsDoc.DEFAULTS.toConfig(source.features.realDrops),
            new Motd(
                source.features.motd,
                source.motd.snapshotRefreshTicks
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
            new Menus(
                source.features.menus,
                (float) source.menus.uiScale,
                source.menus.maxListEntries
            ),
            new Panels(
                source.features.panels, source.panels.visibilityIntervalTicks, source.panels.followIntervalTicks,
                source.panels.permissionCacheTicks
            ),
            new Previews(
                source.features.previews
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
                source.integration.sampleIntervalTicks,
                source.integration.maxSampleAgeMs,
                source.integration.retainUnavailableMs,
                source.integration.errorRetryTicks,
                source.integration.unavailableText,
                source.integration.maxReferencedMetrics,
                source.integration.referenceWindowMs
            ),
            new Modules(
                // --- lane:screen ---
                new Surfaces(source.features.surfaces, source.surfaces.refreshIntervalTicks,
                    source.surfaces.maxBossBarsPerViewer, source.surfaces.titleQueueLimit),
                new Nametags(source.features.nametags, source.nametags.refreshIntervalTicks,
                    source.nametags.snapshotReadLimit, source.nametags.viewerRange,
                    source.nametags.maxSubjectsPerViewer),
                // --- lane:chat ---
                new Leaderboards(source.features.leaderboards, source.leaderboards.sampleIntervalTicks,
                    source.leaderboards.maxEntries),
                new Channels(source.features.channels),
                new Strings(source.features.strings),
                // --- lane:forms ---
                new Inventories(source.features.inventories, source.inventories.closeOnTeleport,
                    source.inventories.unsupportedIconItem),
                // --- lane:world ---
                new Markers(source.features.markers, source.markers.maxPerViewer, source.markers.viewRange,
                    source.markers.anchorSnapshotTicks, source.markers.anchorMaxAgeTicks,
                    source.markers.anchorCacheEntries),
                new Waypoints(source.features.waypoints, source.waypoints.maxPerViewer,
                    source.waypoints.refreshTicks, source.waypoints.positionThreshold,
                    source.waypoints.azimuthThreshold),
                new Camera(source.features.camera, source.camera.maxRideSeconds),
                new Sky(source.features.sky, source.sky.fadeIntervalTicks, source.sky.maxPendingPerViewer, source.sky.maxPendingOperations),
                new Nameplates(source.features.nameplates, source.nameplates.viewerRange, source.nameplates.maxSubjectsPerViewer,
                    source.nameplates.refreshIntervalTicks),
                new Glow(source.features.glow, source.glow.sweepIntervalTicks, source.glow.viewerRange, source.glow.maxTargetsPerViewer),
                // --- lane:behaviors ---
                new Behaviors(source.features.behaviors, source.behaviors.maxActionsPerTick,
                    source.behaviors.maxTimersPerPlayer, source.behaviors.stateFlushSeconds,
                    source.behaviors.chatMaxWorkUnits, source.behaviors.maxTimersGlobal,
                    source.behaviors.maxTimersWithoutPlayer),
                // --- lane:authoring ---
                new GlossPacks(source.features.glosspacks, source.glosspacks.allowServerCommands),
                new History(source.features.history, source.history.maxVersions, source.history.maxAgeDays,
                    source.history.maxTransactionBackups, source.history.maxTransactionBackupBytes),
                // --- lane:forge ---
                new Forge(source.features.forge, source.forge.url, source.forge.serve, source.forge.serveBind,
                    source.forge.servePort, source.forge.required, source.forge.prompt, source.forge.packFormat,
                    source.forge.codepointBase, source.forge.listenerThreads, source.forge.listenerBacklog,
                    source.forge.buildDebounceTicks, source.forge.buildQueueCapacity,
                    new PackLimits(source.forge.maxBuildFiles, source.forge.maxBuildBytes, source.forge.maxBuildPixels,
                        source.forge.maxRetainedArtifacts, source.forge.maxRetainedBytes,
                        source.forge.artifactRetentionSeconds)),
                // --- lane:connections ---
                new Connections(source.features.connections),
                // --- lane:fixes ---
                new Bedrock(source.bedrock.detection, source.bedrock.hideHolograms, source.bedrock.hidePanels,
                    source.bedrock.hideBubbles, source.bedrock.hideIndicators, source.bedrock.hideDrops,
                    source.bedrock.hideOverlays)
            ),
            source.visibility.snapshot(),
            new Images(source.images.maxFileBytes, source.images.maxPixels, source.images.maxDimension,
                source.images.rasterMaxDimension, source.images.cacheBytes, source.images.maxEntries,
                source.images.maxPending, source.images.workerThreads),
            source.teams.snapshot(),
            new Imports(source.imports.maxFileBytes, source.imports.maxPreviewBytes, source.imports.maxFiles,
                source.imports.previewLifetimeSeconds, source.imports.maxPreparedPreviews, source.imports.maxCachedBytes,
                source.imports.maxVisitedEntries, source.imports.maxDirectoryDepth,
                source.imports.maxPreparationBytes, source.imports.maxPreparationMillis)
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
