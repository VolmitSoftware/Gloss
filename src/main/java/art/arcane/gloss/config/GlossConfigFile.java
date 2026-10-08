package art.arcane.gloss.config;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.util.common.PacketTeamAllocator;
import java.util.HashMap;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.service.BudgetedVisibilityGovernor;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.volmlib.util.config.ConfigDescription;
import art.arcane.volmlib.util.config.ConfigDoc;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;

@ConfigDescription("Gloss runtime configuration. Every knob is emitted with a comment, values outside their documented range are clamped back on load, and edits hot-reload while the server runs.")
public final class GlossConfigFile {
    public static final String BUILDER_URL_DEFAULT = "https://gloss.volmitsoftware.com";
    public static final String EDITOR_SYNC_ENDPOINT_DEFAULT = "https://sync.gloss.volmitsoftware.com/v3";

    @ConfigDoc("Default language for players without an override. Missing official translations download when selected; edit messages directly in languages/<locale>.toml. Blank values use en_US.")
    public String language = VolmitLocales.ENGLISH;

    @ConfigDoc("Sends anonymous bStats usage metrics.")
    public boolean metrics = true;

    @ConfigDoc("Prints the Gloss splash screen during startup.")
    public boolean splashScreen = true;

    @ConfigDoc("Master switches for Gloss rendering, chat, menus, previews and server-list features.")
    public Features features = new Features();
    @ConfigDoc("Polling cadence for the shared data, configuration, locale and asset watchdog.")
    public Hotload hotload = new Hotload();
    @ConfigDoc("Persistent and temporary hologram rendering, visibility and animation limits.")
    public Holograms holograms = new Holograms();
    @ConfigDoc("Viewer-targeted particles attached to in-world Gloss renders.")
    public Particles particles = new Particles();
    @ConfigDoc("Scoreboard sidebar refresh settings.")
    public Boards boards = new Boards();
    @ConfigDoc("Tablist refresh settings; authored header, footer and name formats live in tablist.json.")
    public Tablist tablist = new Tablist();
    @ConfigDoc("Server-list response snapshot refresh; presentation and selection live in motd.json.")
    public Motd motd = new Motd();
    @ConfigDoc("Aggregate visible-entity budgets and distance tiers for governed displays.")
    public Visibility visibility = new Visibility();
    @ConfigDoc("File and retained-data bounds for legacy Gloss and HoloUi import previews.")
    public Imports imports = new Imports();
    @ConfigDoc("Player-group resolution settings.")
    public Groups groups = new Groups();
    @ConfigDoc("Emoji permissions and chat-completion behavior.")
    public Emoji emoji = new Emoji();
    @ConfigDoc("Placeholder and function stages of the shared text pipeline.")
    public Text text = new Text();
    @ConfigDoc("Player chat formatting settings.")
    public Chat chat = new Chat();
    @ConfigDoc("Command feedback behavior.")
    public Commands commands = new Commands();
    @ConfigDoc("Operator diagnostics and visual debug overlays.")
    public Debug debug = new Debug();
    @ConfigDoc("Hosted editor and live relay connection settings.")
    public Editor editor = new Editor();
    @ConfigDoc("Shared holographic menu and panel rendering scale.")
    public Menus menus = new Menus();
    @ConfigDoc("Custom item-provider discovery and allowlisting.")
    public Items items = new Items();
    @ConfigDoc("Prepared image decoding, raster limits and bounded cache memory.")
    public Images images = new Images();
    @ConfigDoc("Player-head profile resolution, caching and fallback rendering.")
    public PlayerHeads playerHeads = new PlayerHeads();
    @ConfigDoc("Sampling cadence for metrics published by other Volmit plugins.")
    public Integration integration = new Integration();

    @ConfigDoc("Concurrent temporary-display admissions, separate from per-viewer entity budgets.")
    public TemporaryDisplays temporaryDisplays = new TemporaryDisplays();
    @ConfigDoc("World-panel visibility, follow and access sampling cadences.")
    public Panels panels = new Panels();

    @ConfigDoc("Authored action bar, boss bar and title documents under surfaces/.")
    public Surfaces surfaces = new Surfaces();
    @ConfigDoc("Per-viewer nametag prefixes and suffixes from nametags/ documents.")
    public Nametags nametags = new Nametags();
    @ConfigDoc("Shared nametag, nameplate, overlay and glow team composition and foreign ownership.")
    public Teams teams = new Teams();
    @ConfigDoc("Per-viewer glow lifetime checks and admission limits.")
    public Glow glow = new Glow();
    @ConfigDoc("Player nameplate discovery overrides; zero inherits the entity-overlay document's setting.")
    public Nameplates nameplates = new Nameplates();
    @ConfigDoc("Ranked top-N snapshots from leaderboards/ documents.")
    public Leaderboards leaderboards = new Leaderboards();
    @ConfigDoc("Chest-inventory menus from inventories/ documents.")
    public Inventories inventories = new Inventories();
    @ConfigDoc("World markers, beams and trails from markers/ documents.")
    public Markers markers = new Markers();
    @ConfigDoc("Locator bar waypoints from waypoints/ documents.")
    public Waypoints waypoints = new Waypoints();
    @ConfigDoc("Per-viewer camera rides driven by the camera action.")
    public Camera camera = new Camera();
    @ConfigDoc("Per-viewer sky ownership, restoration and fade scheduling.")
    public Sky sky = new Sky();
    @ConfigDoc("Event-driven behaviors/ documents, their timers and persisted state.")
    public Behaviors behaviors = new Behaviors();
    @ConfigDoc("Generated resource pack: glyph fonts, image atlases and how the pack reaches players.")
    public Forge forge = new Forge();
    @ConfigDoc("Installable .glosspack content bundles.")
    public GlossPacks glosspacks = new GlossPacks();
    @ConfigDoc("Document version history retention.")
    public History history = new History();
    @ConfigDoc("Bedrock (Geyser) viewer detection and which surfaces are withheld from Bedrock clients.")
    public Bedrock bedrock = new Bedrock();

    public static final class Features {
        @ConfigDoc("Enables the hologram engine.")
        public boolean holograms = true;

        @ConfigDoc("Enables scoreboard sidebars.")
        public boolean boards = true;

        @ConfigDoc("Enables tablist header/footer and list-name management.")
        public boolean tablist = true;

        @ConfigDoc("Enables emoji replacement in chat and content.")
        public boolean emoji = true;

        @ConfigDoc("Enables text animations.")
        public boolean animations = true;

        @ConfigDoc("Enables chat bubbles above players.")
        public boolean chatBubbles = true;

        @ConfigDoc("Enables floating damage and heal indicators.")
        public boolean damageIndicators = true;

        @ConfigDoc("Enables custom names on dropped item stacks.")
        public boolean drops = true;

        @ConfigDoc("Replaces vanilla dropped-item rendering with grounded, tumbling display models and display-backed labels.")
        public boolean realDrops = true;

        @ConfigDoc("Enables holographic menus.")
        public boolean menus = true;

        @ConfigDoc("Enables world-anchored panels.")
        public boolean panels = true;

        @ConfigDoc("Enables look-at container previews.")
        public boolean previews = true;

        @ConfigDoc("Enables the custom server list MOTD.")
        public boolean motd = false;

        @ConfigDoc("Enables join and leave messages from connections.json.")
        public boolean connections = false;

        @ConfigDoc("Enables particle layers attached to in-world renders.")
        public boolean particles = true;

        @ConfigDoc("Enables authored action bar, boss bar and title surfaces.")
        public boolean surfaces = true;

        @ConfigDoc("Enables per-viewer nametag prefixes and suffixes.")
        public boolean nametags = false;

        @ConfigDoc("Enables leaderboard sampling.")
        public boolean leaderboards = true;

        @ConfigDoc("Enables the chat channel engine; off keeps the legacy emoji and color chat pass.")
        public boolean channels = false;

        @ConfigDoc("Enables author-owned strings/ catalogs for lang() in content.")
        public boolean strings = true;

        @ConfigDoc("Enables chest-inventory menus.")
        public boolean inventories = true;

        @ConfigDoc("Enables world markers.")
        public boolean markers = true;

        @ConfigDoc("Enables locator bar waypoints.")
        public boolean waypoints = true;

        @ConfigDoc("Enables camera rides.")
        public boolean camera = true;

        @ConfigDoc("Enables per-viewer sky, weather and border overrides.")
        public boolean sky = true;

        @ConfigDoc("Replaces the vanilla player nametag with a Gloss nameplate pane.")
        public boolean nameplates = false;

        @ConfigDoc("Enables per-viewer entity glow tagging.")
        public boolean glow = true;

        @ConfigDoc("Enables behaviors documents.")
        public boolean behaviors = true;

        @ConfigDoc("Enables resource pack generation.")
        public boolean forge = false;

        @ConfigDoc("Enables .glosspack installation commands.")
        public boolean glosspacks = true;

        @ConfigDoc("Enables document version history.")
        public boolean history = true;
    }

    public static final class Hotload {
        @ConfigDoc("Ticks between polls of watched files for hot reloading. Clamped to 1..200.")
        public int watchIntervalTicks = 5;
    }

    public static final class Holograms {
        @ConfigDoc("Distance in blocks at which holograms become visible. Clamped to 4..128.")
        public double viewRange = 48.0D;

        @ConfigDoc("Renders complete placeholder, function and expression tokens per viewing player instead of once globally.")
        public boolean perViewerPlaceholders = true;

        @ConfigDoc("Ticks between refreshes of temporary holograms. Clamped to 1..20.")
        public int temporaryUpdateIntervalTicks = 2;

        @ConfigDoc("Uses client-side Display interpolation for moving temporary holograms, falling back to teleports when the server API lacks it.")
        public boolean interpolatedMotion = true;

        @ConfigDoc("Drives fast animated hologram lines from a dedicated async thread instead of the tick updater.")
        public boolean highFrequencyAnimations = true;

        @ConfigDoc("Maximum frames per second the high-frequency animation thread targets. Clamped to 1..240.")
        public int maxAnimationFps = 120;

        @ConfigDoc("Animation text packets per second allowed across each animated display's audience; large audiences degrade frame rate proportionally. Clamped to 100..1000000.")
        public int animationPacketBudget = 20000;

    }

    public static final class Motd {
        @ConfigDoc("Ticks between prepared server-list response refreshes. Clamped to 1..1200.")
        public int snapshotRefreshTicks = 20;
    }

    public static final class Imports {
        @ConfigDoc("Maximum bytes in one imported source or observed destination file. Clamped to 1024..268435456.")
        public int maxFileBytes = 16777216;
        @ConfigDoc("Maximum retained source and staged replacement bytes in one preview. Clamped to maxFileBytes..1073741824.")
        public int maxPreviewBytes = 134217728;
        @ConfigDoc("Maximum source and destination paths retained by one preview. Clamped to 1..65536.")
        public int maxFiles = 4096;
        @ConfigDoc("Maximum entries visited across project document collections per validation scan, including directories, ignored files and symbolic links. Clamped to 1..1048576.")
        public int maxVisitedEntries = 65536;
        @ConfigDoc("Maximum nested directory depth in recursive document collections; the collection root is depth 0. Deeper directories reject the preview. Clamped to 1..128.")
        public int maxDirectoryDepth = 16;
        @ConfigDoc("Lifetime in seconds of a reviewed import preview. Clamped to 1..86400; captured when the preview is saved.")
        public int previewLifetimeSeconds = 600;
        @ConfigDoc("Maximum reviewed previews retained across senders and import formats. Clamped to 1..1024.")
        public int maxPreparedPreviews = 8;
        @ConfigDoc("Maximum total retained source and staged bytes in reviewed previews. Clamped to maxPreviewBytes..1073741824.")
        public int maxCachedBytes = 268435456;

        @ConfigDoc("Maximum staged replacement and backup bytes in one import publication. Excludes retained prior backups. Clamped to 1024..17179869184.")
        public long maxPreparationBytes = 1073741824L;
        @ConfigDoc("Cooperative preparation deadline in milliseconds before import publication starts. Checked between operations and streaming chunks; does not interrupt a blocked filesystem call or recovery. Clamped to 1..600000.")
        public long maxPreparationMillis = 30000L;

        private void normalize() {
            maxPreparationBytes = Math.clamp(maxPreparationBytes, 1024L, 17179869184L);
            maxPreparationMillis = Math.clamp(maxPreparationMillis, 1L, 600000L);
            maxFileBytes = clampInt(maxFileBytes, 1024, 268435456);
            maxPreviewBytes = clampInt(maxPreviewBytes, maxFileBytes, 1073741824);
            maxFiles = clampInt(maxFiles, 1, 65536);
            maxVisitedEntries = clampInt(maxVisitedEntries, 1, 1048576);
            maxDirectoryDepth = clampInt(maxDirectoryDepth, 1, 128);
            previewLifetimeSeconds = clampInt(previewLifetimeSeconds, 1, 86400);
            maxPreparedPreviews = clampInt(maxPreparedPreviews, 1, 1024);
            maxCachedBytes = clampInt(maxCachedBytes, maxPreviewBytes, 1073741824);
        }
    }

    public static final class Panels {
        @ConfigDoc("Owner ticks between panel visibility/range checks. Active menu contents keep their own cadence; editing previews and world changes refresh immediately. Clamped to 1..1200.")
        public int visibilityIntervalTicks = 1;
        @ConfigDoc("Ticks between applying captured panel follow poses. Active views can follow independently of visibility checks. Clamped to 1..1200.")
        public int followIntervalTicks = 1;
        @ConfigDoc("Ticks to reuse a panel permission result (50ms per tick); 0 checks every visibility evaluation. Clamped to 0..1200.")
        public int permissionCacheTicks = 20;
    }

    public static final class TemporaryDisplays {
        @ConfigDoc("Maximum concurrent chat bubbles across senders. Lowering the limit refuses new bubbles until active bubbles retire. Clamped to 1..1048576.")
        public int maxActiveBubbles = 2048;
        @ConfigDoc("Maximum concurrent damage/healing indicators across entities, also bounded by the authored rate and lifetime. Clamped to 1..1048576.")
        public int maxActiveIndicators = 2048;
    }

    public static final class Visibility {
        @ConfigDoc("Maximum visible entity instances across all viewers. Clamped to 1..10000000.")
        public int perServer = 65536;
        @ConfigDoc("Maximum visible entity instances for one viewer. Clamped to 1..1000000.")
        public int perViewer = 1024;
        @ConfigDoc("Admission ordering: reject retries immediately; fifo gives waiting viewer/surface pairs turns without preempting active displays.")
        public String admission = "reject";
        @ConfigDoc("Maximum waiting viewer/surface pairs in FIFO admission. Clamped to 1..65536.")
        public int maxPending = 4096;
        @ConfigDoc("FIFO turn lifetime from the first refusal, in ticks. Retries do not extend it. Clamped to 1..72000.")
        public int queueTimeoutTicks = 100;
        @ConfigDoc("Limits used by surfaces without an override.")
        public VisibilityPolicy defaults = new VisibilityPolicy();
        @ConfigDoc("Named surface overrides. Reservations across all surfaces must fit within perViewer.")
        public List<VisibilitySurface> surfaces = new ArrayList<>();

        public BudgetedVisibilityGovernor.Limits snapshot() {
            Map<VisibilityGovernor.Surface, BudgetedVisibilityGovernor.Policy> policies = new EnumMap<>(VisibilityGovernor.Surface.class);
            for (VisibilitySurface surface : surfaces) {
                VisibilityGovernor.Surface key = VisibilityGovernor.Surface.valueOf(surface.surface.trim().toUpperCase(Locale.ROOT));
                if (policies.put(key, surface.limits.snapshot()) != null) {
                    throw new IllegalArgumentException("Duplicate visibility surface: " + surface.surface);
                }
            }
            return new BudgetedVisibilityGovernor.Limits(perServer, perViewer, defaults.snapshot(), policies,
                new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.valueOf(admission.toUpperCase(Locale.ROOT)),
                    maxPending, queueTimeoutTicks));
        }

        private void normalize() {
            perServer = clampInt(perServer, 1, 10000000);
            perViewer = clampInt(perViewer, 1, 1000000);
            admission = normalizePolicy(admission, "reject", List.of("reject", "fifo"));
            maxPending = clampInt(maxPending, 1, 65536);
            queueTimeoutTicks = clampInt(queueTimeoutTicks, 1, 72000);
            defaults = defaults == null ? new VisibilityPolicy() : defaults;
            defaults.normalize();
            surfaces = surfaces == null ? new ArrayList<>() : new ArrayList<>(surfaces);
            for (VisibilitySurface surface : surfaces) {
                if (surface == null || surface.surface == null || surface.surface.isBlank()) {
                    throw new IllegalArgumentException("Visibility surface overrides require a surface name");
                }
                surface.limits = surface.limits == null ? new VisibilityPolicy() : surface.limits;
                surface.limits.normalize();
            }
            snapshot();
        }
    }

    public static final class VisibilitySurface {
        public String surface = "menu";
        public VisibilityPolicy limits = new VisibilityPolicy();
    }

    public static final class VisibilityPolicy {
        @ConfigDoc("Maximum visible entity instances for this surface across viewers. Clamped to 1..10000000.")
        public int perServer = 16384;
        @ConfigDoc("Maximum visible entity instances of this surface for one viewer. Clamped to 1..1000000.")
        public int perViewer = 512;
        @ConfigDoc("Viewer capacity held for this surface even while it is idle. Clamped to 0..perViewer.")
        public int reservedPerViewer = 0;
        @ConfigDoc("Server capacity held for this surface even while idle. Clamped to 0..perServer; all reservations must fit the global server budget.")
        public int reservedPerServer = 0;
        @ConfigDoc("Distance through which full detail is requested. Clamped to 0..1000000 blocks.")
        public double fullDistance = 32;
        @ConfigDoc("Distance through which reduced detail is requested; farther displays request minimal detail.")
        public double reducedDistance = 64;
        @ConfigDoc("Distance beyond which governed displays are culled.")
        public double cullDistance = 128;

        private void normalize() {
            perServer = clampInt(perServer, 1, 10000000);
            perViewer = clampInt(perViewer, 1, 1000000);
            reservedPerViewer = clampInt(reservedPerViewer, 0, perViewer);
            reservedPerServer = clampInt(reservedPerServer, 0, perServer);
            fullDistance = clampDouble(fullDistance, 0, 1000000, 32);
            reducedDistance = clampDouble(reducedDistance, fullDistance, 1000000, Math.max(64, fullDistance));
            cullDistance = clampDouble(cullDistance, reducedDistance, 1000000, Math.max(128, reducedDistance));
        }

        private BudgetedVisibilityGovernor.Policy snapshot() {
            return new BudgetedVisibilityGovernor.Policy(perServer, perViewer, reservedPerViewer, reservedPerServer,
                fullDistance, reducedDistance, cullDistance);
        }
    }

    public static final class Particles {
        @ConfigDoc("Particle samples admitted for one viewer in one tick. Clamped to 1..4096.")
        public int samplesPerViewerPerTick = 128;

        @ConfigDoc("Particle samples admitted server-wide in one tick. Clamped to 16..65536.")
        public int samplesPerTick = 4096;

        @ConfigDoc("Maximum local geometry samples cached or generated for one layer. Clamped to 4..4096.")
        public int maxCachedSamplesPerLayer = 512;
    }

    public static final class Boards {
        @ConfigDoc(
            "Ticks between ordinary scoreboard refreshes. Active boards with clock-driven expressions or named "
                + "animations automatically sample every tick. Clamped to 1..200."
        )
        public int updateIntervalTicks = 20;
    }

    public static final class Tablist {
        @ConfigDoc("Maximum captured field and provider values per player for tablist conditions and text. Clamped to 16..65536.")
        public int snapshotReadLimit = 4096;
        @ConfigDoc(
            "Ticks between ordinary tablist refreshes. Clock-driven expressions and named animations automatically "
                + "sample every tick. Clamped to 1..400."
        )
        public int updateIntervalTicks = 40;
    }

    public static final class Groups {
        @ConfigDoc("Resolves player groups through Vault when it is installed.")
        public boolean useVault = true;
    }

    public static final class Emoji {
        @ConfigDoc("Requires a per-emoji permission instead of the global emoji permission.")
        public boolean emojiSpecificPermissions = false;

        @ConfigDoc("Offers emoji triggers in chat tab completion.")
        public boolean tabComplete = true;
    }

    public static final class Text {
        @ConfigDoc("Resolves PlaceholderAPI placeholders in rendered text.")
        public boolean placeholders = true;

        @ConfigDoc("Resolves |function| expressions in rendered text.")
        public boolean functions = true;
    }

    public static final class Chat {
        @ConfigDoc("Translates color codes in player chat for permitted players.")
        public boolean color = true;
    }

    public static final class Commands {
        @ConfigDoc("Plays feedback sounds when command output is delivered.")
        public boolean sounds = true;
    }

    public static final class Debug {
        @ConfigDoc("Renders menu hitbox debug outlines for all sessions.")
        public boolean hitbox = false;

        @ConfigDoc("Renders menu position debug markers for all sessions.")
        public boolean position = false;

        @ConfigDoc("Logs high-frequency animator loop statistics every 10 seconds.")
        public boolean animator = false;
    }

    public static final class Editor {
        @ConfigDoc("Base URL of the hosted web editor; must be a plain http(s) link or the default is used.")
        public String builderUrl = BUILDER_URL_DEFAULT;

        @ConfigDoc("Live web-editor relay authentication, polling and project limits.")
        public Sync sync = new Sync();

        public static final class Sync {
            @ConfigDoc("Enables live editor sync sessions through the relay.")
            public boolean enabled = true;

            @ConfigDoc("Relay endpoint URL; must be https (or loopback http) ending in /v3 or the default is used.")
            public String endpoint = EDITOR_SYNC_ENDPOINT_DEFAULT;

            @ConfigDoc("Relay session creation token of 22..128 characters from A-Z, a-z, 0-9, _ and -; anything else is treated as empty.")
            public String createToken = "";

            @ConfigDoc("Minutes an editor sync session stays alive. Clamped to 5..1440.")
            public int sessionMinutes = 60;

            @ConfigDoc("Seconds between relay polls during an active session. Clamped to 1..60.")
            public int pollSeconds = 3;

            @ConfigDoc("Maximum editor sync project size in mebibytes. Clamped to 1..32.")
            public int maxProjectMiB = 8;
        }
    }

    public static final class Menus {
        @ConfigDoc("Global render scale multiplier for holographic menus and panels. Clamped to 0.25..4.0.")
        public double uiScale = 1.0D;

        @ConfigDoc("Maximum entries one menu list component may expand into display entities. Clamped to 1..512.")
        public int maxListEntries = 64;
    }

    public static final class Items {
        @ConfigDoc("Enables custom item icons resolved through installed item plugins.")
        public boolean customItems = true;

        @ConfigDoc("Item provider allowlist by provider or plugin name; an empty list allows every provider.")
        public List<String> customItemProviders = new ArrayList<>();
    }

    public static final class PlayerHeads {
        @ConfigDoc("Resolves playerHead icons into real player skins. Off renders every player-head icon as the unknown-name fallback and makes no outbound request.")
        public boolean enabled = true;

        @ConfigDoc("Minutes a resolved player profile stays cached. Clamped to 1..10080.")
        public int cacheMinutes = 360;

        @ConfigDoc("Minutes a name confirmed not to exist stays cached before it is looked up again. Clamped to 1..1440.")
        public int unknownCacheMinutes = 10;

        @ConfigDoc("Maximum cached profiles. The entries closest to expiry are dropped first; a lookup still in flight is never dropped. Clamped to 16..65536.")
        public int maxCachedProfiles = 2048;

        @ConfigDoc("Block shown in place of a name that does not exist or could not be read. Anything that is not a real block falls back to minecraft:skeleton_skull.")
        public String unknownFallbackItem = "minecraft:skeleton_skull";
    }

    public static final class Integration {
        @ConfigDoc("Maximum distinct metric references retained for sampling. Least recently requested keys are evicted before admitting new keys. Clamped to 1..65536.")
        public int maxReferencedMetrics = 256;

        @ConfigDoc("Milliseconds a metric remains demanded after its last use. Clamped to 1..86400000.")
        public long referenceWindowMs = 60000L;

        @ConfigDoc("Ticks between samples of the metrics other Volmit plugins publish for |metric.<key>| and preview variables. Clamped to 1..200.")
        public int sampleIntervalTicks = 20;

        @ConfigDoc("Maximum provider sample age in milliseconds. Zero accepts any timestamp. Clamped to 0..86400000.")
        public int maxSampleAgeMs = 5000;

        @ConfigDoc("Milliseconds to retain the last successful value when a provider is unavailable. Zero clears it immediately. Clamped to 0..86400000.")
        public int retainUnavailableMs = 0;

        @ConfigDoc("Ticks before retrying a provider that throws during sampling. Clamped to 1..12000.")
        public int errorRetryTicks = 100;

        @ConfigDoc("Text rendered for registered metrics without an available value. Limited to 1024 characters.")
        public String unavailableText = "";
    }

    // Lane sections. A lane adds knobs inside its own class and its own clamp anchor in normalize().
    // --- lane:screen ---
    public static final class Surfaces {
        @ConfigDoc("Ticks between surface document refreshes. Clamped to 1..200.")
        public int refreshIntervalTicks = 10;

        @ConfigDoc("Boss bars one viewer may see from Gloss surfaces at once. Clamped to 1..64.")
        public int maxBossBarsPerViewer = 3;

        @ConfigDoc("Queued titles per viewer before older ones are dropped. Clamped to 1..64.")
        public int titleQueueLimit = 8;
    }

    private static String normalizePolicy(String value, String fallback, List<String> accepted) {
        String normalized = value == null ? fallback : value.trim().toLowerCase(Locale.ROOT);
        return accepted.contains(normalized) ? normalized : fallback;
    }

    public static final class Teams {
        @ConfigDoc("Foreign scoreboard membership: yield preserves another plugin's team; override claims it for Gloss and restores observed foreign membership on release.")
        public String foreignPolicy = "yield";
        @ConfigDoc("Composition priority by purpose, highest first. Values clamp to -1000000..1000000. Unknown purposes use zero.")
        public Map<String, Integer> layerPriorities = new HashMap<>(PacketTeamAllocator.Policy.DEFAULTS.layerPriorities());
        @ConfigDoc("Name-tag visibility composition: intersection applies every restriction; priority uses the strongest layer's rule.")
        public String visibilityPolicy = "intersection";
        @ConfigDoc("Collision composition: intersection applies every restriction; priority uses the strongest layer's rule.")
        public String collisionPolicy = "intersection";
        @ConfigDoc("Treat white as an unspecified layer color, allowing a lower-priority color to show.")
        public boolean whiteIsUnspecified = true;

        public PacketTeamAllocator.Policy snapshot() {
            return new PacketTeamAllocator.Policy(
                PacketTeamAllocator.ForeignPolicy.valueOf(foreignPolicy.toUpperCase(Locale.ROOT)), layerPriorities,
                PacketTeamAllocator.Composition.valueOf(visibilityPolicy.toUpperCase(Locale.ROOT)),
                PacketTeamAllocator.Composition.valueOf(collisionPolicy.toUpperCase(Locale.ROOT)), whiteIsUnspecified);
        }
    }

    public static final class Nameplates {
        @ConfigDoc("Nameplate viewing range in blocks; zero inherits the overlay document. Clamped to 0..64.")
        public double viewerRange = 0;
        @ConfigDoc("Nearest subjects considered per viewer; zero shares the overlay document's population limit. Clamped to 0..256.")
        public int maxSubjectsPerViewer = 0;
        @ConfigDoc("Nameplate refresh cadence in ticks; zero inherits the overlay document. Clamped to 0..200.")
        public int refreshIntervalTicks = 0;
    }

    public static final class Glow {
        @ConfigDoc("Ticks between glow expiry and distance checks. Clamped to 1..200.")
        public int sweepIntervalTicks = 20;
        @ConfigDoc("Maximum glow distance in blocks; zero leaves distance unrestricted. Clamped to 0..512.")
        public double viewerRange = 0;
        @ConfigDoc("Distinct glow targets accepted per viewer. Further tag requests fail explicitly. Clamped to 1..65536.")
        public int maxTargetsPerViewer = 1024;
    }

    public static final class Nametags {
        @ConfigDoc("Ticks between nametag re-evaluations. Clamped to 1..200.")
        public int refreshIntervalTicks = 20;

        @ConfigDoc("Distinct captured values and provider calls retained per player for nametag conditions and text. Clamped to 16..65536.")
        public int snapshotReadLimit = 4096;

        @ConfigDoc("Viewing distance in blocks for nametags with viewer-dependent conditions or text. Clamped to 1..512.")
        public double viewerRange = 64.0D;

        @ConfigDoc("Nearest subjects evaluated per viewer for viewer-dependent nametags. Clamped to 1..10000.")
        public int maxSubjectsPerViewer = 32;
    }

    // --- lane:chat ---
    public static final class Leaderboards {
        @ConfigDoc("Ticks between leaderboard samples. Clamped to 20..72000.")
        public int sampleIntervalTicks = 1200;

        @ConfigDoc("Maximum ranked entries kept per leaderboard. Clamped to 1..1000.")
        public int maxEntries = 100;
    }

    // --- lane:forms ---
    public static final class Inventories {
        @ConfigDoc("Closes an open inventory menu when its viewer teleports.")
        public boolean closeOnTeleport = true;

        @ConfigDoc("Item drawn in a chest slot for icon kinds that only exist as display entities (textImage, animatedTextImage, entity).")
        public String unsupportedIconItem = "minecraft:paper";
    }

    // --- lane:world ---
    public static final class Markers {
        @ConfigDoc("Markers one viewer may see at once; the nearest win. Clamped to 1..64.")
        public int maxPerViewer = 12;

        @ConfigDoc("Distance in blocks at which markers stop rendering. Clamped to 16..1024.")
        public double viewRange = 256.0D;

        @ConfigDoc("Minimum ticks between owner-captured moving-anchor samples shared by markers and waypoints. Clamped to 1..1200.")
        public int anchorSnapshotTicks = 2;

        @ConfigDoc("Oldest moving-anchor sample that may render while a refresh is pending. Clamped to anchorSnapshotTicks..12000.")
        public int anchorMaxAgeTicks = 100;

        @ConfigDoc("Maximum cached entity/player anchors shared across viewers. Clamped to 16..65536.")
        public int anchorCacheEntries = 4096;
    }

    public static final class Waypoints {
        @ConfigDoc("Ticks between locator-bar updates. Clamped to 1..1200.")
        public int refreshTicks = 20;

        @ConfigDoc("Minimum target movement before a position update, in blocks. Clamped to 0..64.")
        public double positionThreshold = 1.0D;

        @ConfigDoc("Minimum bearing movement before a direction update, in radians. Clamped to 0..3.141592653589793.")
        public double azimuthThreshold = 0.017D;

        @ConfigDoc("Locator bar waypoints one viewer may track at once. Clamped to 1..64.")
        public int maxPerViewer = 16;
    }

    public static final class Camera {
        @ConfigDoc("Longest camera ride in seconds; longer rides are cut and the viewer restored. Clamped to 1..3600.")
        public int maxRideSeconds = 120;
    }

    // --- lane:behaviors ---
    public static final class Behaviors {
        @ConfigDoc("Shared regular-expression work units per chat event across all behavior documents. Clamped to 1..100000000; excess matching is skipped without dropping chat.")
        public int chatMaxWorkUnits = 2000000;

        @ConfigDoc("Behavior actions executed per tick across the server before the rest defer. Clamped to 16..65536.")
        public int maxActionsPerTick = 256;

        @ConfigDoc("Pending delayed or repeating behavior timers per player. Clamped to 1..256.")
        public int maxTimersPerPlayer = 16;

        @ConfigDoc("Pending delayed action continuations across all players and playerless runs. Clamped to 1..1048576; a full budget refuses new continuations.")
        public int maxTimersGlobal = 8192;

        @ConfigDoc("Pending delayed action continuations without a player, also counted against maxTimersGlobal. Clamped to 1..65536.")
        public int maxTimersWithoutPlayer = 256;

        @ConfigDoc("Seconds between persisted state flushes. Clamped to 1..600.")
        public int stateFlushSeconds = 30;
    }

    // --- lane:authoring ---
    public static final class Images {
        @ConfigDoc("Maximum bytes read from one image source. Clamped to 1024..268435456.")
        public int maxFileBytes = 16777216;
        @ConfigDoc("Maximum decoded source pixels. Clamped to 256..67108864.")
        public int maxPixels = 16777216;
        @ConfigDoc("Maximum source width or height. Clamped to 16..8192.")
        public int maxDimension = 4096;
        @ConfigDoc("Maximum text-raster width or height; larger images need a declared pack glyph. Clamped to 1..128.")
        public int rasterMaxDimension = 16;
        @ConfigDoc("Prepared image cache weight limit in bytes. Clamped to 1048576..1073741824.")
        public int cacheBytes = 67108864;
        @ConfigDoc("Maximum cached image paths, including failed sources. Clamped to 1..4096.")
        public int maxEntries = 256;
        @ConfigDoc("Maximum image preparations waiting or running. Clamped to 1..4096.")
        public int maxPending = 128;
        @ConfigDoc("Concurrent image decoding workers. Clamped to 1..4; changes apply after restart.")
        public int workerThreads = 1;
    }

    public static final class GlossPacks {
        @ConfigDoc("Allows installed packs to carry command actions with source server. Off strips them at install.")
        public boolean allowServerCommands = false;
    }

    public static final class History {
        @ConfigDoc("Versions kept per document. Clamped to 1..500.")
        public int maxVersions = 20;

        @ConfigDoc("Days a version is kept before pruning. Clamped to 1..3650.")
        public int maxAgeDays = 30;

        @ConfigDoc("Completed transaction archives retained across editor sync, imports, pack installs, and history restores. Recovery data and the newest intact committed backup with originals remain protected. Applies even when history is disabled. Clamped to 1..1000.")
        public int maxTransactionBackups = 20;

        @ConfigDoc("Aggregate retained transaction file bytes, including journals and staged files. Protected backups can exceed this target. Clamped to 1048576..68719476736.")
        public long maxTransactionBackupBytes = 1073741824L;
    }

    // --- lane:forge ---
    public static final class Sky {
        @ConfigDoc("Ticks between owner-scheduled sky fade updates. Clamped to 1..200.")
        public int fadeIntervalTicks = 2;
        @ConfigDoc("Maximum pending sky operations for one viewer. Clamped to 1..1024.")
        public int maxPendingPerViewer = 64;
        @ConfigDoc("Maximum pending sky operations across viewers. Clamped to 16..65536.")
        public int maxPendingOperations = 1024;
    }

    public static final class Forge {
        @ConfigDoc("Embedded pack HTTP worker threads. Clamped to 1..32.")
        public int listenerThreads = 4;
        @ConfigDoc("Embedded pack HTTP connection and queued request backlog. Clamped to 1..4096.")
        public int listenerBacklog = 32;
        @ConfigDoc("Quiet ticks before rebuilding changed glyph files. Clamped to 1..1200.")
        public int buildDebounceTicks = 100;
        @ConfigDoc("Waiting pack build/export jobs behind the active job. Clamped to 1..64.")
        public int buildQueueCapacity = 1;

        @ConfigDoc("Maximum generated files in one resource pack. Clamped to 2..65536.")
        public int maxBuildFiles = 8192;
        @ConfigDoc("Maximum total generated file bytes and maximum ZIP bytes per build, each checked separately. Clamped to 1024..1073741824.")
        public long maxBuildBytes = 67108864L;
        @ConfigDoc("Maximum decoded texture pixels across a build; repeated texture outputs count separately. Clamped to 1..1073741824.")
        public long maxBuildPixels = 67108864L;
        @ConfigDoc("Maximum retained immutable pack ZIPs. Protected packs can prevent a rebuild until released. Clamped to 2..4096.")
        public int maxRetainedArtifacts = 8;
        @ConfigDoc("Maximum total bytes of retained immutable pack ZIPs, excluding the bounded working tree and staging files. Clamped to 1024..17179869184.")
        public long maxRetainedBytes = 536870912L;
        @ConfigDoc("Minimum seconds to keep an old pack after publication or release of its last offer/download. Clamped to 1..2592000. Current packs and outstanding offers/downloads remain protected.")
        public long artifactRetentionSeconds = 600;

        @ConfigDoc("Public URL players download the generated pack from; {sha1} is replaced with the current pack hash. Blank disables pack delivery unless serve is on.")
        public String url = "";

        @ConfigDoc("Serves the generated pack from an embedded HTTP listener; needs a port reachable by players.")
        public boolean serve = false;

        @ConfigDoc("Bind address for the embedded pack listener.")
        public String serveBind = "0.0.0.0";

        @ConfigDoc("Port for the embedded pack listener. Clamped to 1024..65535.")
        public int servePort = 8085;

        @ConfigDoc("Marks the pack as required so declining it disconnects the player.")
        public boolean required = false;

        @ConfigDoc("Prompt shown with the pack request.")
        public String prompt = "Gloss glyphs and icons";

        @ConfigDoc("Resource pack format written to pack.mcmeta; 0 picks the value for the running server version.")
        public int packFormat = 0;

        @ConfigDoc("First private-use codepoint the glyph ledger allocates from. Clamped to 57344..63488 (U+E000..U+F800); raise it to sit above another glyph plugin's range.")
        public int codepointBase = 57344;
    }

    // --- lane:fixes ---
    public static final class Bedrock {
        @ConfigDoc("Bedrock viewer detection: auto (Floodgate, then Geyser), floodgate, geyser, uuid (Floodgate UUID shape), or off.")
        public String detection = "auto";

        @ConfigDoc("Withholds holograms from Bedrock viewers; text displays do not render on Bedrock.")
        public boolean hideHolograms = true;

        @ConfigDoc("Withholds panels and hologram menus from Bedrock viewers.")
        public boolean hidePanels = true;

        @ConfigDoc("Withholds chat bubbles from Bedrock viewers.")
        public boolean hideBubbles = true;

        @ConfigDoc("Withholds damage indicators from Bedrock viewers.")
        public boolean hideIndicators = true;

        @ConfigDoc("Withholds real-drop presentations from Bedrock viewers.")
        public boolean hideDrops = true;

        @ConfigDoc("Withholds entity overlays from Bedrock viewers.")
        public boolean hideOverlays = true;
    }

    public void normalize() {
        language = language == null || language.isBlank() ? VolmitLocales.ENGLISH : language.trim();
        if (features == null) {
            features = new Features();
        }
        if (motd == null) {
            motd = new Motd();
        }
        motd.snapshotRefreshTicks = clampInt(motd.snapshotRefreshTicks, 1, 1200);
        visibility = visibility == null ? new Visibility() : visibility;
        visibility.normalize();
        imports = imports == null ? new Imports() : imports;
        imports.normalize();
        if (playerHeads == null) {
            playerHeads = new PlayerHeads();
        }
        if (hotload == null) {
            hotload = new Hotload();
        }
        if (holograms == null) {
            holograms = new Holograms();
        }
        if (particles == null) {
            particles = new Particles();
        }
        if (boards == null) {
            boards = new Boards();
        }
        if (tablist == null) {
            tablist = new Tablist();
        }
        if (groups == null) {
            groups = new Groups();
        }
        if (emoji == null) {
            emoji = new Emoji();
        }
        if (text == null) {
            text = new Text();
        }
        if (chat == null) {
            chat = new Chat();
        }
        if (commands == null) {
            commands = new Commands();
        }
        if (debug == null) {
            debug = new Debug();
        }
        if (editor == null) {
            editor = new Editor();
        }
        if (editor.sync == null) {
            editor.sync = new Editor.Sync();
        }
        if (menus == null) {
            menus = new Menus();
        }
        if (items == null) {
            items = new Items();
        }
        if (images == null) {
            images = new Images();
        }
        if (integration == null) {
            integration = new Integration();
        }
        if (surfaces == null) {
            surfaces = new Surfaces();
        }
        if (panels == null) {
            panels = new Panels();
        }
        panels.visibilityIntervalTicks = clampInt(panels.visibilityIntervalTicks, 1, 1200);
        panels.followIntervalTicks = clampInt(panels.followIntervalTicks, 1, 1200);
        panels.permissionCacheTicks = clampInt(panels.permissionCacheTicks, 0, 1200);
        if (temporaryDisplays == null) {
            temporaryDisplays = new TemporaryDisplays();
        }
        temporaryDisplays.maxActiveBubbles = clampInt(temporaryDisplays.maxActiveBubbles, 1, 1048576);
        temporaryDisplays.maxActiveIndicators = clampInt(temporaryDisplays.maxActiveIndicators, 1, 1048576);
        if (teams == null) teams = new Teams();
        if (glow == null) glow = new Glow();
        if (nameplates == null) nameplates = new Nameplates();
        if (nametags == null) {
            nametags = new Nametags();
        }
        if (leaderboards == null) {
            leaderboards = new Leaderboards();
        }
        if (inventories == null) {
            inventories = new Inventories();
        }
        if (markers == null) {
            markers = new Markers();
        }
        if (waypoints == null) {
            waypoints = new Waypoints();
        }
        if (camera == null) {
            camera = new Camera();
        }
        if (behaviors == null) {
            behaviors = new Behaviors();
        }
        if (sky == null) {
            sky = new Sky();
        }
        if (forge == null) {
            forge = new Forge();
        }
        if (glosspacks == null) {
            glosspacks = new GlossPacks();
        }
        if (history == null) {
            history = new History();
        }
        if (bedrock == null) {
            bedrock = new Bedrock();
        }

        hotload.watchIntervalTicks = clampInt(hotload.watchIntervalTicks, 1, 200);

        images.maxFileBytes = clampInt(images.maxFileBytes, 1024, 268435456);
        images.maxPixels = clampInt(images.maxPixels, 256, 67108864);
        images.maxDimension = clampInt(images.maxDimension, 16, 8192);
        images.rasterMaxDimension = clampInt(images.rasterMaxDimension, 1, 128);
        images.cacheBytes = clampInt(images.cacheBytes, 1048576, 1073741824);
        images.maxEntries = clampInt(images.maxEntries, 1, 4096);
        images.maxPending = clampInt(images.maxPending, 1, 4096);
        images.workerThreads = clampInt(images.workerThreads, 1, 4);

        integration.maxReferencedMetrics = clampInt(integration.maxReferencedMetrics, 1, 65536);
        integration.referenceWindowMs = Math.max(1L, Math.min(86400000L, integration.referenceWindowMs));
        integration.sampleIntervalTicks = clampInt(integration.sampleIntervalTicks, 1, 200);
        integration.maxSampleAgeMs = clampInt(integration.maxSampleAgeMs, 0, 86400000);
        integration.retainUnavailableMs = clampInt(integration.retainUnavailableMs, 0, 86400000);
        integration.errorRetryTicks = clampInt(integration.errorRetryTicks, 1, 12000);
        integration.unavailableText = integration.unavailableText == null ? "" : integration.unavailableText;
        if (integration.unavailableText.length() > 1024) {
            integration.unavailableText = integration.unavailableText.substring(0, 1024);
        }

        holograms.viewRange = clampDouble(holograms.viewRange, 4.0D, 128.0D, 48.0D);
        holograms.temporaryUpdateIntervalTicks = clampInt(holograms.temporaryUpdateIntervalTicks, 1, 20);
        holograms.maxAnimationFps = clampInt(holograms.maxAnimationFps, 1, 240);
        holograms.animationPacketBudget = clampInt(holograms.animationPacketBudget, 100, 1_000_000);

        particles.samplesPerViewerPerTick = clampInt(particles.samplesPerViewerPerTick, 1, 4096);
        particles.samplesPerTick = clampInt(particles.samplesPerTick, 16, 65536);
        particles.maxCachedSamplesPerLayer = clampInt(particles.maxCachedSamplesPerLayer, 4, 4096);

        boards.updateIntervalTicks = clampInt(boards.updateIntervalTicks, 1, 200);

        tablist.updateIntervalTicks = clampInt(tablist.updateIntervalTicks, 1, 400);
        tablist.snapshotReadLimit = clampInt(tablist.snapshotReadLimit, 16, 65536);


        editor.builderUrl = sanitizeBuilderUrl(editor.builderUrl);
        editor.sync.endpoint = sanitizeSyncEndpoint(editor.sync.endpoint);
        editor.sync.createToken = sanitizeSyncCreateToken(editor.sync.createToken);
        editor.sync.sessionMinutes = clampInt(editor.sync.sessionMinutes, 5, 1440);
        editor.sync.pollSeconds = clampInt(editor.sync.pollSeconds, 1, 60);
        editor.sync.maxProjectMiB = clampInt(editor.sync.maxProjectMiB, 1, 32);


        menus.uiScale = clampDouble(menus.uiScale, 0.25D, 4.0D, 1.0D);

        items.customItemProviders = normalizeProviders(items.customItemProviders);

        playerHeads.cacheMinutes = clampInt(playerHeads.cacheMinutes, 1, 10080);
        playerHeads.unknownCacheMinutes = clampInt(playerHeads.unknownCacheMinutes, 1, 1440);
        playerHeads.maxCachedProfiles = clampInt(playerHeads.maxCachedProfiles, 16, 65536);
        playerHeads.unknownFallbackItem = orDefault(playerHeads.unknownFallbackItem, "minecraft:skeleton_skull");

        // Lane clamps: each lane clamps its own section inside its own anchor.
        // --- lane:screen ---
        surfaces.refreshIntervalTicks = clampInt(surfaces.refreshIntervalTicks, 1, 200);
        surfaces.maxBossBarsPerViewer = clampInt(surfaces.maxBossBarsPerViewer, 1, 64);
        surfaces.titleQueueLimit = clampInt(surfaces.titleQueueLimit, 1, 64);
        teams.foreignPolicy = normalizePolicy(teams.foreignPolicy, "yield", List.of("yield", "override"));
        teams.visibilityPolicy = normalizePolicy(teams.visibilityPolicy, "intersection", List.of("intersection", "priority"));
        teams.collisionPolicy = normalizePolicy(teams.collisionPolicy, "intersection", List.of("intersection", "priority"));
        if (teams.layerPriorities == null) teams.layerPriorities = new HashMap<>(PacketTeamAllocator.Policy.DEFAULTS.layerPriorities());
        teams.layerPriorities.replaceAll((purpose, priority) -> clampInt(priority == null ? 0 : priority, -1000000, 1000000));
        teams.layerPriorities.keySet().removeIf(String::isBlank);
        nameplates.viewerRange = clampDouble(nameplates.viewerRange, 0, 64, 0);
        nameplates.maxSubjectsPerViewer = clampInt(nameplates.maxSubjectsPerViewer, 0, 256);
        nameplates.refreshIntervalTicks = clampInt(nameplates.refreshIntervalTicks, 0, 200);
        glow.sweepIntervalTicks = clampInt(glow.sweepIntervalTicks, 1, 200);
        glow.viewerRange = clampDouble(glow.viewerRange, 0, 512, 0);
        glow.maxTargetsPerViewer = clampInt(glow.maxTargetsPerViewer, 1, 65536);
        nametags.refreshIntervalTicks = clampInt(nametags.refreshIntervalTicks, 1, 200);
        nametags.snapshotReadLimit = clampInt(nametags.snapshotReadLimit, 16, 65536);
        nametags.viewerRange = clampDouble(nametags.viewerRange, 1.0D, 512.0D, 64.0D);
        nametags.maxSubjectsPerViewer = clampInt(nametags.maxSubjectsPerViewer, 1, 10000);

        // --- lane:chat ---
        leaderboards.sampleIntervalTicks = clampInt(leaderboards.sampleIntervalTicks, 20, 72000);
        leaderboards.maxEntries = clampInt(leaderboards.maxEntries, 1, 1000);

        // --- lane:forms ---
        menus.maxListEntries = clampInt(menus.maxListEntries, 1, 512);
        inventories.unsupportedIconItem = inventories.unsupportedIconItem == null || inventories.unsupportedIconItem.isBlank()
            ? "minecraft:paper" : inventories.unsupportedIconItem.trim();

        // --- lane:world ---
        markers.maxPerViewer = clampInt(markers.maxPerViewer, 1, 64);
        markers.viewRange = clampDouble(markers.viewRange, 16.0D, 1024.0D, 256.0D);
        markers.anchorSnapshotTicks = clampInt(markers.anchorSnapshotTicks, 1, 1200);
        markers.anchorMaxAgeTicks = clampInt(markers.anchorMaxAgeTicks, markers.anchorSnapshotTicks, 12000);
        markers.anchorCacheEntries = clampInt(markers.anchorCacheEntries, 16, 65536);
        waypoints.maxPerViewer = clampInt(waypoints.maxPerViewer, 1, 64);
        waypoints.refreshTicks = clampInt(waypoints.refreshTicks, 1, 1200);
        waypoints.positionThreshold = clampDouble(waypoints.positionThreshold, 0.0D, 64.0D, 1.0D);
        waypoints.azimuthThreshold = clampDouble(waypoints.azimuthThreshold, 0.0D, Math.PI, 0.017D);
        camera.maxRideSeconds = clampInt(camera.maxRideSeconds, 1, 3600);

        // --- lane:behaviors ---
        behaviors.maxActionsPerTick = clampInt(behaviors.maxActionsPerTick, 16, 65536);
        behaviors.maxTimersPerPlayer = clampInt(behaviors.maxTimersPerPlayer, 1, 256);
        behaviors.maxTimersGlobal = clampInt(behaviors.maxTimersGlobal, 1, 1048576);
        behaviors.maxTimersWithoutPlayer = clampInt(behaviors.maxTimersWithoutPlayer, 1, 65536);
        behaviors.stateFlushSeconds = clampInt(behaviors.stateFlushSeconds, 1, 600);
        behaviors.chatMaxWorkUnits = clampInt(behaviors.chatMaxWorkUnits, 1, 100000000);

        // --- lane:authoring ---
        history.maxVersions = clampInt(history.maxVersions, 1, 500);
        history.maxAgeDays = clampInt(history.maxAgeDays, 1, 3650);
        history.maxTransactionBackups = clampInt(history.maxTransactionBackups, 1, 1000);
        history.maxTransactionBackupBytes = Math.clamp(history.maxTransactionBackupBytes, 1048576L, 68719476736L);

        // --- lane:forge ---
        sky.fadeIntervalTicks = clampInt(sky.fadeIntervalTicks, 1, 200);
        sky.maxPendingPerViewer = clampInt(sky.maxPendingPerViewer, 1, 1024);
        sky.maxPendingOperations = clampInt(sky.maxPendingOperations, 16, 65536);
        forge.listenerThreads = clampInt(forge.listenerThreads, 1, 32);
        forge.listenerBacklog = clampInt(forge.listenerBacklog, 1, 4096);
        forge.buildDebounceTicks = clampInt(forge.buildDebounceTicks, 1, 1200);
        forge.buildQueueCapacity = clampInt(forge.buildQueueCapacity, 1, 64);
        forge.maxBuildFiles = clampInt(forge.maxBuildFiles, 2, 65536);
        forge.maxBuildBytes = Math.clamp(forge.maxBuildBytes, 1024L, 1073741824L);
        forge.maxBuildPixels = Math.clamp(forge.maxBuildPixels, 1L, 1073741824L);
        forge.maxRetainedArtifacts = clampInt(forge.maxRetainedArtifacts, 2, 4096);
        forge.maxRetainedBytes = Math.clamp(forge.maxRetainedBytes, 1024L, 17179869184L);
        forge.artifactRetentionSeconds = Math.clamp(forge.artifactRetentionSeconds, 1L, 2592000L);
        forge.url = forge.url == null ? "" : forge.url.strip();
        forge.serveBind = orDefault(forge.serveBind, "0.0.0.0");
        forge.servePort = clampInt(forge.servePort, 1024, 65535);
        forge.prompt = orDefault(forge.prompt, "Gloss glyphs and icons");
        forge.packFormat = clampInt(forge.packFormat, 0, 1000);
        forge.codepointBase = clampInt(forge.codepointBase, 57344, 63488);

        // --- lane:fixes ---
        bedrock.detection = normalizeChoice(bedrock.detection, "AUTO", "FLOODGATE", "GEYSER", "UUID", "OFF").toLowerCase(Locale.ROOT);
    }

    public static String sanitizeBuilderUrl(String configured) {
        if (configured == null) {
            return BUILDER_URL_DEFAULT;
        }
        String trimmed = configured.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return BUILDER_URL_DEFAULT;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (current <= ' ' || current == '\'' || current == '"' || current == '<' || current == '>' || current == '\\') {
                return BUILDER_URL_DEFAULT;
            }
        }
        return trimmed;
    }

    public static String sanitizeSyncEndpoint(String configured) {
        if (configured == null || !configured.equals(configured.strip())) {
            return EDITOR_SYNC_ENDPOINT_DEFAULT;
        }
        String sanitized = configured;
        while (sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(sanitized).normalize();
        } catch (IllegalArgumentException failure) {
            return EDITOR_SYNC_ENDPOINT_DEFAULT;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null || !uri.isAbsolute()) {
            return EDITOR_SYNC_ENDPOINT_DEFAULT;
        }
        boolean https = scheme.equalsIgnoreCase("https");
        boolean loopbackHttp = scheme.equalsIgnoreCase("http") && isLoopbackHost(host);
        String path = uri.getPath();
        if ((!https && !loopbackHttp) || path == null || !path.endsWith("/v3")
            || path.contains("//") || path.contains("/../") || path.contains("/./")) {
            return EDITOR_SYNC_ENDPOINT_DEFAULT;
        }
        try {
            String normalized = new URI(scheme.toLowerCase(Locale.ROOT), null, host.toLowerCase(Locale.ROOT),
                uri.getPort(), path, null, null).toString();
            return normalized.length() <= 1024 ? normalized : EDITOR_SYNC_ENDPOINT_DEFAULT;
        } catch (URISyntaxException failure) {
            return EDITOR_SYNC_ENDPOINT_DEFAULT;
        }
    }

    public static String sanitizeSyncCreateToken(String configured) {
        if (configured == null || configured.isBlank()) {
            return "";
        }
        String normalized = configured.strip();
        if (!normalized.equals(configured) || normalized.length() < 22 || normalized.length() > 128
            || !normalized.matches("[A-Za-z0-9_-]+")) {
            Gloss.log(Level.WARNING,
                "editor.sync.createToken is invalid; live editor session creation will use no token.");
            return "";
        }
        return normalized;
    }

    private static boolean isLoopbackHost(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
            || host.equals("::1") || host.equals("[::1]");
    }

    private static List<String> cleanStrings(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<String> cleaned = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null) {
                cleaned.add(value);
            }
        }
        return cleaned;
    }

    private static List<String> normalizeProviders(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String cleaned = value.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty() && !normalized.contains(cleaned)) {
                normalized.add(cleaned);
            }
        }
        return normalized;
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clampDouble(double value, double minimum, double maximum, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String normalizeChoice(String value, String... choices) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        for (String choice : choices) {
            if (choice.equals(normalized)) {
                return choice;
            }
        }
        return choices[0];
    }
}
