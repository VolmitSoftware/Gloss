package art.arcane.gloss.config;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.config.ConfigDescription;
import art.arcane.volmlib.util.config.ConfigDoc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

@ConfigDescription("Gloss runtime configuration. Every knob is emitted with a comment, values outside their documented range are clamped back on load, and edits hot-reload while the server runs.")
public final class GlossConfigFile {
    public static final String BUILDER_URL_DEFAULT = "https://gloss.volmitsoftware.com";
    public static final String BUNDLE_ENTRY_FORMAT_DEFAULT = "&7- &f{count}x {type}";
    public static final String BUNDLE_FORMAT_DEFAULT = "&7Bundle &8(&7{total} items&8): &7{contents}";
    public static final String BUNDLE_HEADER_FORMAT_DEFAULT = "&eBundle &8(&e{total} items&8)";
    public static final String BUNDLE_MORE_FORMAT_DEFAULT = "&8+{remaining} more";
    public static final String EDITOR_SYNC_ENDPOINT_DEFAULT = "https://sync.gloss.volmitsoftware.com/v2";

    @ConfigDoc("Prints the Gloss splash screen during startup.")
    public boolean splashScreen = true;

    @ConfigDoc("Sends anonymous bStats usage metrics.")
    public boolean metrics = true;

    public Features features = new Features();
    public Hotload hotload = new Hotload();
    public Holograms holograms = new Holograms();
    public Boards boards = new Boards();
    public Tablist tablist = new Tablist();
    public Groups groups = new Groups();
    public Emoji emoji = new Emoji();
    public Text text = new Text();
    public Chat chat = new Chat();
    public ChatBubbles chatBubbles = new ChatBubbles();
    public DamageIndicators damageIndicators = new DamageIndicators();
    public Drops drops = new Drops();
    public RealDrops realDrops = new RealDrops();
    public Commands commands = new Commands();
    public Debug debug = new Debug();
    public Editor editor = new Editor();
    public Preview preview = new Preview();
    public Menus menus = new Menus();
    public Items items = new Items();
    public Integration integration = new Integration();

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
    }

    public static final class Hotload {
        @ConfigDoc("Ticks between polls of watched files for hot reloading. Clamped to 1..200.")
        public int watchIntervalTicks = 5;
    }

    public static final class Holograms {
        @ConfigDoc("Vertical distance in blocks between stacked hologram lines. Clamped to 0.05..2.0.")
        public double stackDistance = 0.26D;

        @ConfigDoc("Ticks between hologram text refreshes. Clamped to 1..200.")
        public int updateIntervalTicks = 10;

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

        @ConfigDoc("Maximum width in characters for rendered text art. Clamped to 8..128.")
        public int textArtMaxWidth = 48;
    }

    public static final class Boards {
        @ConfigDoc("Ticks between scoreboard refreshes. Clamped to 1..200.")
        public int updateIntervalTicks = 20;
    }

    public static final class Tablist {
        @ConfigDoc("Ticks between tablist refreshes. Clamped to 1..400.")
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

    public static final class ChatBubbles {
        @ConfigDoc("World folder names where chat bubbles never appear.")
        public List<String> blacklistWorlds = new ArrayList<>();
    }

    public static final class DamageIndicators {
        @ConfigDoc("Random sideways force applied to spawned indicators. Clamped to 0.0..2.0.")
        public double randomThrowForce = 0.08D;

        @ConfigDoc("Initial upward force applied to spawned indicators. Clamped to 0.0..2.0.")
        public double initialUpForce = 0.13D;

        @ConfigDoc("Gravity pull applied to indicators each tick. Clamped to 0.0..1.0.")
        public double gravityFactor = 0.0093D;

        @ConfigDoc("Maximum indicators spawned per second before new ones are dropped. Clamped to 1..1000.")
        public int maxPerSecond = 40;

        @ConfigDoc("Milliseconds an indicator stays alive. Clamped to 250..30000.")
        public long maxMsAlive = 3000L;

        @ConfigDoc("Color-code prefix applied to damage numbers.")
        public String damagePrefix = "&c&l";

        @ConfigDoc("Color-code prefix applied to heal numbers.")
        public String healPrefix = "&a&l";

        @ConfigDoc("Decimal places shown on indicator numbers. Clamped to 0..2.")
        public int decimals = 0;

        @ConfigDoc("Shows heal indicators in addition to damage indicators.")
        public boolean showHeals = true;
    }

    public static final class Drops {
        @ConfigDoc("Name format for dropped item stacks; {count} and {type} are replaced.")
        public String nameFormat = "&7{count}x {type}";

        @ConfigDoc("Name format for dropped bundles that carry stacks; {total} and {contents} are replaced. Empty bundles use nameFormat.")
        public String bundleFormat = BUNDLE_FORMAT_DEFAULT;

        @ConfigDoc("Bundle content entries listed before the rest collapse into a +N more suffix. Clamped to 1..10.")
        public int bundleEntryLimit = 3;

        @ConfigDoc("Uses a vertical TextDisplay label for bundles while realDrops is enabled.")
        public boolean bundleVerticalLabels = true;

        @ConfigDoc("Header format for vertical bundle labels; {total} is replaced.")
        public String bundleHeaderFormat = BUNDLE_HEADER_FORMAT_DEFAULT;

        @ConfigDoc("Entry format for vertical bundle labels; {count} and {type} are replaced.")
        public String bundleEntryFormat = BUNDLE_ENTRY_FORMAT_DEFAULT;

        @ConfigDoc("Remainder format for vertical bundle labels; {remaining} is replaced with the hidden material count.")
        public String bundleMoreFormat = BUNDLE_MORE_FORMAT_DEFAULT;

        @ConfigDoc("Leaves custom names other plugins already set on dropped items untouched.")
        public boolean preserveCustomNames = true;

        @ConfigDoc("Uses an item's display name from its item meta as {type} instead of the material name.")
        public boolean useItemDisplayNames = true;
    }

    public static final class RealDrops {
        public Limits limits = new Limits();
        public Scale scale = new Scale();
        public Motion motion = new Motion();
        public Landing landing = new Landing();
        public Labels labels = new Labels();
        public Filters filters = new Filters();

        public static final class Limits {
            @ConfigDoc("Ticks between airborne carrier-position and motion updates. Client interpolation smooths the gaps. Clamped to 1..20.")
            public int updateIntervalTicks = 2;

            @ConfigDoc("Ticks between cheap carrier-position and stack checks after an item has settled. Clamped to 2..200.")
            public int settledPollIntervalTicks = 20;

            @ConfigDoc("Maximum ItemDisplay models used to suggest stack size. Clamped to 1..5.")
            public int maxVisualsPerStack = 3;

            @ConfigDoc("Maximum Gloss-owned ItemDisplay and TextDisplay entities in one chunk. Clamped to 8..1024.")
            public int maxVisualsPerChunk = 128;

            @ConfigDoc("Client tracking range in blocks for item models. Clamped to 4..128.")
            public double viewRange = 32.0D;

            @ConfigDoc("Distance between additional stack models. Clamped to 0.0..1.0.")
            public double spread = 0.18D;
        }

        public static final class Scale {
            @ConfigDoc("Scale used by ordinary block models. Clamped to 0.05..2.0.")
            public double defaultScale = 0.4D;

            @ConfigDoc("Scale used by flat item models. Clamped to 0.05..2.0.")
            public double flatItems = 0.65D;

            @ConfigDoc("Scale used by slabs, carpets and other thin block models. Clamped to 0.05..2.0.")
            public double thinBlocks = 0.45D;
        }

        public static final class Motion {
            @ConfigDoc("Tumbles real-drop models while their item entity is airborne.")
            public boolean tumble = true;

            @ConfigDoc("Airborne X-axis tumble speed in degrees per second. Clamped to -1440..1440.")
            public double degreesPerSecondX = 160.0D;

            @ConfigDoc("Airborne Y-axis tumble speed in degrees per second. Clamped to -1440..1440.")
            public double degreesPerSecondY = 120.0D;

            @ConfigDoc("Airborne Z-axis tumble speed in degrees per second. Clamped to -1440..1440.")
            public double degreesPerSecondZ = 100.0D;

            @ConfigDoc("Per-item deterministic tumble-speed variation. 0 is uniform and 1 allows a full-speed difference. Clamped to 0..1.")
            public double variance = 0.2D;

            @ConfigDoc("Chooses a new deterministic tumble direction when an item bounces upward.")
            public boolean changeOnBounce = true;
        }

        public static final class Landing {
            @ConfigDoc("Settled orientation: NATURAL keeps blocks mostly upright, FLAT lays every model down, and UPRIGHT removes tilt.")
            public String mode = "NATURAL";

            @ConfigDoc("Maximum deterministic landing tilt for NATURAL mode in degrees. Clamped to 0..45.")
            public double tiltDegrees = 10.0D;

            @ConfigDoc("Gives each settled model a deterministic yaw instead of aligning every drop identically.")
            public boolean randomYaw = true;

            @ConfigDoc("Client interpolation ticks used to ease from airborne tumble into the landing pose. Clamped to 0..20.")
            public int transitionTicks = 4;
        }

        public static final class Labels {
            @ConfigDoc("Mirrors Gloss drop names into TextDisplay labels because the native item entity is hidden.")
            public boolean enabled = true;

            @ConfigDoc("Vertical label offset above the item model. Clamped to 0..4.")
            public double yOffset = 0.55D;

            @ConfigDoc("Label display scale. Clamped to 0.1..4.")
            public double scale = 0.85D;

            @ConfigDoc("Client tracking range in blocks for drop labels. Clamped to 4..128.")
            public double viewRange = 32.0D;

            @ConfigDoc("Label facing mode: CENTER, FIXED, HORIZONTAL or VERTICAL.")
            public String billboard = "CENTER";

            @ConfigDoc("Renders labels through blocks.")
            public boolean seeThrough = false;

            @ConfigDoc("Draws the vanilla text shadow behind label glyphs.")
            public boolean shadow = true;

            @ConfigDoc("Draws a colored background behind the full label block.")
            public boolean background = true;

            @ConfigDoc("Drop-label background red channel. Clamped to 0..255.")
            public int backgroundRed = 0;

            @ConfigDoc("Drop-label background green channel. Clamped to 0..255.")
            public int backgroundGreen = 0;

            @ConfigDoc("Drop-label background blue channel. Clamped to 0..255.")
            public int backgroundBlue = 0;

            @ConfigDoc("Drop-label background opacity. 0 is transparent and 255 is opaque. Clamped to 0..255.")
            public int backgroundAlpha = 80;
        }

        public static final class Filters {
            @ConfigDoc("World folder names where real-drop models are never created.")
            public List<String> disabledWorlds = new ArrayList<>();

            @ConfigDoc("Materials that keep vanilla dropped-item rendering.")
            public List<String> materialBlacklist = new ArrayList<>(List.of("BEDROCK", "BARRIER"));

            @ConfigDoc("Limits real-drop models to item entities with a non-null thrower UUID.")
            public boolean onlyPlayerDrops = false;
        }
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

        public Sync sync = new Sync();

        public static final class Sync {
            @ConfigDoc("Enables live editor sync sessions through the relay.")
            public boolean enabled = true;

            @ConfigDoc("Relay endpoint URL; must be https (or loopback http) ending in /v2 or the default is used.")
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

    public static final class Preview {
        @ConfigDoc("Distance in blocks the look-at raycast reaches for container previews. Clamped to 1.0..24.0.")
        public double lookDistance = 10.0D;

        @ConfigDoc("Base render scale of container previews. Clamped to 0.25..4.0.")
        public double scale = 0.65D;
    }

    public static final class Menus {
        @ConfigDoc("Global render scale multiplier for holographic menus and panels. Clamped to 0.25..4.0.")
        public double uiScale = 1.0D;
    }

    public static final class Items {
        @ConfigDoc("Enables custom item icons resolved through installed item plugins.")
        public boolean customItems = true;

        @ConfigDoc("Item provider allowlist by provider or plugin name; an empty list allows every provider.")
        public List<String> customItemProviders = new ArrayList<>();
    }

    public static final class Integration {
        @ConfigDoc("Ticks between samples of the metrics other Volmit plugins publish for |metric.<key>| and preview variables. Clamped to 1..200.")
        public int sampleIntervalTicks = 20;
    }

    public void normalize() {
        if (features == null) {
            features = new Features();
        }
        if (hotload == null) {
            hotload = new Hotload();
        }
        if (holograms == null) {
            holograms = new Holograms();
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
        if (chatBubbles == null) {
            chatBubbles = new ChatBubbles();
        }
        if (damageIndicators == null) {
            damageIndicators = new DamageIndicators();
        }
        if (drops == null) {
            drops = new Drops();
        }
        if (realDrops == null) {
            realDrops = new RealDrops();
        }
        if (realDrops.limits == null) {
            realDrops.limits = new RealDrops.Limits();
        }
        if (realDrops.scale == null) {
            realDrops.scale = new RealDrops.Scale();
        }
        if (realDrops.motion == null) {
            realDrops.motion = new RealDrops.Motion();
        }
        if (realDrops.landing == null) {
            realDrops.landing = new RealDrops.Landing();
        }
        if (realDrops.labels == null) {
            realDrops.labels = new RealDrops.Labels();
        }
        if (realDrops.filters == null) {
            realDrops.filters = new RealDrops.Filters();
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
        if (preview == null) {
            preview = new Preview();
        }
        if (menus == null) {
            menus = new Menus();
        }
        if (items == null) {
            items = new Items();
        }
        if (integration == null) {
            integration = new Integration();
        }

        hotload.watchIntervalTicks = clampInt(hotload.watchIntervalTicks, 1, 200);

        integration.sampleIntervalTicks = clampInt(integration.sampleIntervalTicks, 1, 200);

        holograms.stackDistance = clampDouble(holograms.stackDistance, 0.05D, 2.0D, 0.26D);
        holograms.updateIntervalTicks = clampInt(holograms.updateIntervalTicks, 1, 200);
        holograms.viewRange = clampDouble(holograms.viewRange, 4.0D, 128.0D, 48.0D);
        holograms.temporaryUpdateIntervalTicks = clampInt(holograms.temporaryUpdateIntervalTicks, 1, 20);
        holograms.textArtMaxWidth = clampInt(holograms.textArtMaxWidth, 8, 128);
        holograms.maxAnimationFps = clampInt(holograms.maxAnimationFps, 1, 240);
        holograms.animationPacketBudget = clampInt(holograms.animationPacketBudget, 100, 1_000_000);

        boards.updateIntervalTicks = clampInt(boards.updateIntervalTicks, 1, 200);

        tablist.updateIntervalTicks = clampInt(tablist.updateIntervalTicks, 1, 400);

        chatBubbles.blacklistWorlds = cleanStrings(chatBubbles.blacklistWorlds);

        damageIndicators.randomThrowForce = clampDouble(damageIndicators.randomThrowForce, 0.0D, 2.0D, 0.08D);
        damageIndicators.initialUpForce = clampDouble(damageIndicators.initialUpForce, 0.0D, 2.0D, 0.13D);
        damageIndicators.gravityFactor = clampDouble(damageIndicators.gravityFactor, 0.0D, 1.0D, 0.0093D);
        damageIndicators.maxPerSecond = clampInt(damageIndicators.maxPerSecond, 1, 1000);
        damageIndicators.maxMsAlive = clampLong(damageIndicators.maxMsAlive, 250L, 30000L);
        damageIndicators.damagePrefix = orDefault(damageIndicators.damagePrefix, "&c&l");
        damageIndicators.healPrefix = orDefault(damageIndicators.healPrefix, "&a&l");
        damageIndicators.decimals = clampInt(damageIndicators.decimals, 0, 2);

        drops.nameFormat = orDefault(drops.nameFormat, "&7{count}x {type}");
        drops.bundleFormat = orDefault(drops.bundleFormat, BUNDLE_FORMAT_DEFAULT);
        drops.bundleEntryLimit = clampInt(drops.bundleEntryLimit, 1, 10);
        drops.bundleHeaderFormat = orDefault(drops.bundleHeaderFormat, BUNDLE_HEADER_FORMAT_DEFAULT);
        drops.bundleEntryFormat = orDefault(drops.bundleEntryFormat, BUNDLE_ENTRY_FORMAT_DEFAULT);
        drops.bundleMoreFormat = orDefault(drops.bundleMoreFormat, BUNDLE_MORE_FORMAT_DEFAULT);

        realDrops.limits.updateIntervalTicks = clampInt(realDrops.limits.updateIntervalTicks, 1, 20);
        realDrops.limits.settledPollIntervalTicks = clampInt(realDrops.limits.settledPollIntervalTicks, 2, 200);
        realDrops.limits.maxVisualsPerStack = clampInt(realDrops.limits.maxVisualsPerStack, 1, 5);
        realDrops.limits.maxVisualsPerChunk = clampInt(realDrops.limits.maxVisualsPerChunk, 8, 1024);
        realDrops.limits.viewRange = clampDouble(realDrops.limits.viewRange, 4.0D, 128.0D, 32.0D);
        realDrops.limits.spread = clampDouble(realDrops.limits.spread, 0.0D, 1.0D, 0.18D);
        realDrops.scale.defaultScale = clampDouble(realDrops.scale.defaultScale, 0.05D, 2.0D, 0.4D);
        realDrops.scale.flatItems = clampDouble(realDrops.scale.flatItems, 0.05D, 2.0D, 0.65D);
        realDrops.scale.thinBlocks = clampDouble(realDrops.scale.thinBlocks, 0.05D, 2.0D, 0.45D);
        realDrops.motion.degreesPerSecondX = clampDouble(realDrops.motion.degreesPerSecondX, -1440.0D, 1440.0D, 160.0D);
        realDrops.motion.degreesPerSecondY = clampDouble(realDrops.motion.degreesPerSecondY, -1440.0D, 1440.0D, 120.0D);
        realDrops.motion.degreesPerSecondZ = clampDouble(realDrops.motion.degreesPerSecondZ, -1440.0D, 1440.0D, 100.0D);
        realDrops.motion.variance = clampDouble(realDrops.motion.variance, 0.0D, 1.0D, 0.2D);
        realDrops.landing.mode = normalizeChoice(realDrops.landing.mode, "NATURAL", "FLAT", "UPRIGHT");
        realDrops.landing.tiltDegrees = clampDouble(realDrops.landing.tiltDegrees, 0.0D, 45.0D, 10.0D);
        realDrops.landing.transitionTicks = clampInt(realDrops.landing.transitionTicks, 0, 20);
        realDrops.labels.yOffset = clampDouble(realDrops.labels.yOffset, 0.0D, 4.0D, 0.55D);
        realDrops.labels.scale = clampDouble(realDrops.labels.scale, 0.1D, 4.0D, 0.85D);
        realDrops.labels.viewRange = clampDouble(realDrops.labels.viewRange, 4.0D, 128.0D, 32.0D);
        realDrops.labels.billboard = normalizeChoice(realDrops.labels.billboard, "CENTER", "FIXED", "HORIZONTAL", "VERTICAL");
        realDrops.labels.backgroundRed = clampInt(realDrops.labels.backgroundRed, 0, 255);
        realDrops.labels.backgroundGreen = clampInt(realDrops.labels.backgroundGreen, 0, 255);
        realDrops.labels.backgroundBlue = clampInt(realDrops.labels.backgroundBlue, 0, 255);
        realDrops.labels.backgroundAlpha = clampInt(realDrops.labels.backgroundAlpha, 0, 255);
        realDrops.filters.disabledWorlds = cleanStrings(realDrops.filters.disabledWorlds);
        realDrops.filters.materialBlacklist = cleanStrings(realDrops.filters.materialBlacklist);

        editor.builderUrl = sanitizeBuilderUrl(editor.builderUrl);
        editor.sync.endpoint = sanitizeSyncEndpoint(editor.sync.endpoint);
        editor.sync.createToken = sanitizeSyncCreateToken(editor.sync.createToken);
        editor.sync.sessionMinutes = clampInt(editor.sync.sessionMinutes, 5, 1440);
        editor.sync.pollSeconds = clampInt(editor.sync.pollSeconds, 1, 60);
        editor.sync.maxProjectMiB = clampInt(editor.sync.maxProjectMiB, 1, 32);

        preview.lookDistance = clampDouble(preview.lookDistance, 1.0D, 24.0D, 10.0D);
        preview.scale = clampDouble(preview.scale, 0.25D, 4.0D, 0.65D);

        menus.uiScale = clampDouble(menus.uiScale, 0.25D, 4.0D, 1.0D);

        items.customItemProviders = normalizeProviders(items.customItemProviders);
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
        if ((!https && !loopbackHttp) || path == null || !path.endsWith("/v2")
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

    private static long clampLong(long value, long minimum, long maximum) {
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
