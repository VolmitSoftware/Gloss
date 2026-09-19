package art.arcane.gloss.doc;

import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.menu.MenuCatalog;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.tab.TablistDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Lane imports: each lane adds its document classes inside its own anchor.
// --- lane:screen ---
import art.arcane.gloss.nametag.NametagDoc;
import art.arcane.gloss.surface.SurfaceDoc;

// --- lane:chat ---
import art.arcane.gloss.chat.ChannelDoc;
import art.arcane.gloss.leaderboard.LeaderboardDoc;
import art.arcane.gloss.strings.StringsDoc;

// --- lane:forms ---
import art.arcane.gloss.inventory.InventoryDoc;

// --- lane:world ---
import art.arcane.gloss.nameplate.NameplateDoc;

// --- lane:behaviors ---
import art.arcane.gloss.behavior.BehaviorDoc;

// --- lane:authoring ---

// --- lane:forge ---
import art.arcane.gloss.forge.GlyphDoc;

// --- lane:connections ---
import art.arcane.gloss.connection.ConnectionsDoc;

// --- lane:fixes ---

public final class ShippedDocumentCatalog {
    public record Entry<T>(String kind, List<String> names, DocumentParser<T> parser) {
        public Entry {
            kind = Objects.requireNonNull(kind, "kind");
            names = List.copyOf(names);
            parser = Objects.requireNonNull(parser, "parser");
        }
    }

    public static final Entry<EmojiDoc> EMOJI = new Entry<>(EmojiDoc.KIND, List.of(
        "heart", "airplane", "mail", "cut", "pencil", "nib", "check", "thickcheck", "cross", "thickcross",
        "star", "darkstarstar", "snowflake", "sparkle", "sun", "left", "right", "up", "down", "leftright",
        "updown", "upleft", "upright", "downright", "downleft", "tapedrive", "thickplus", "lineplus",
        "opencross", "thickopencross", "maltcross", "starofdavid", "heavystar", "blackdiamond",
        "vbar1", "vbar2", "vbar3",
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
        "1t", "2t", "3t", "4t", "5t", "6t", "7t", "8t", "9t", "10t",
        "1h", "2h", "3h", "4h", "5h", "6h", "7h", "8h", "9h", "10h"), EmojiDoc::parse);

    public static final Entry<AnimationDoc> ANIMATIONS =
        new Entry<>(AnimationDoc.KIND, List.of(
            "rainbow", "marquee", "timeline", "typewriter", "flash", "wipe", "scanner", "decode",
            "odometer", "wave"), AnimationDoc::parse);

    public static final Entry<BoardDoc> BOARDS =
        new Entry<>(BoardDoc.KIND, List.of("default", "animation-showcase"), BoardDoc::parse);

    public static final Entry<BubbleStyleDoc> BUBBLES =
        new Entry<>(BubbleStyleDoc.KIND, List.of("default"), BubbleStyleDoc::parse);

    public static final Entry<TablistDoc> TABLIST =
        new Entry<>(TablistDoc.KIND, List.of("tablist"), TablistDoc::parse);

    public static final Entry<MotdDoc> MOTD =
        new Entry<>(MotdDoc.KIND, List.of("motd"), MotdDoc::parse);

    public static final Entry<RealDropSettingsDoc> REAL_DROPS =
        new Entry<>(RealDropSettingsDoc.KIND, List.of(RealDropSettingsDoc.DEFAULT_ID),
            RealDropSettingsDoc::parse);

    public static final Entry<DamageIndicatorSettingsDoc> DAMAGE_INDICATORS =
        new Entry<>(DamageIndicatorSettingsDoc.KIND, List.of(DamageIndicatorSettingsDoc.DEFAULT_ID),
            DamageIndicatorSettingsDoc::parse);

    public static final Entry<MenuDefinitionData> MENUS =
        new Entry<>(MenuCatalog.KIND, List.of("default"), (fileName, raw) ->
            MenuDocumentParser.parse(ShippedDefaults.normalize(fileName), raw).definition());

    public static final Entry<EntityOverlayDoc> ENTITY_OVERLAYS =
        new Entry<>(EntityOverlayDoc.KIND, List.of(EntityOverlayDoc.DEFAULT_ID), EntityOverlayDoc::parse);

    private ShippedDocumentCatalog() {
    }

    private static final List<Entry<?>> CORE = List.of(EMOJI, ANIMATIONS, BOARDS, BUBBLES, TABLIST, MOTD,
        REAL_DROPS, DAMAGE_INDICATORS, ENTITY_OVERLAYS, MENUS);

    // Lane catalogs: a lane declares its Entry constants and lists them in its own constant so no
    // two branches touch the same line. ShippedDocumentTest pins every entry listed here.
    // --- lane:screen ---
    public static final Entry<SurfaceDoc> SURFACES =
        new Entry<>(SurfaceDoc.KIND, List.of("welcome"), SurfaceDoc::parse);

    public static final Entry<NametagDoc> NAMETAGS =
        new Entry<>(NametagDoc.KIND, List.of("default"), NametagDoc::parse);

    private static final List<Entry<?>> SCREEN = List.of(SURFACES, NAMETAGS);

    // --- lane:chat ---
    public static final Entry<ChannelDoc> CHANNELS =
        new Entry<>(ChannelDoc.KIND, List.of("global", "private"), ChannelDoc::parse);

    public static final Entry<StringsDoc> STRINGS =
        new Entry<>(StringsDoc.KIND, List.of(StringsDoc.DEFAULT_ID), StringsDoc::parse);

    public static final Entry<LeaderboardDoc> LEADERBOARDS =
        new Entry<>(LeaderboardDoc.KIND, List.of("playtime"), LeaderboardDoc::parse);

    private static final List<Entry<?>> CHAT = List.of(CHANNELS, STRINGS, LEADERBOARDS);

    // --- lane:forms ---

    public static final Entry<InventoryDoc> INVENTORIES =
        new Entry<>(InventoryDoc.KIND, List.of("example"), InventoryDoc::parse);

    private static final List<Entry<?>> FORMS = List.of(INVENTORIES);

    // --- lane:world ---
    public static final Entry<NameplateDoc> NAMEPLATES =
        new Entry<>(NameplateDoc.KIND, List.of(NameplateDoc.DEFAULT_ID), NameplateDoc::parse);

    private static final List<Entry<?>> WORLD = List.of(NAMEPLATES);

    // --- lane:behaviors ---
    public static final Entry<BehaviorDoc> BEHAVIORS_ENTRY =
        new Entry<>(BehaviorDoc.KIND, List.of("welcome"), BehaviorDoc::parse);
    private static final List<Entry<?>> BEHAVIORS = List.of(BEHAVIORS_ENTRY);

    // --- lane:authoring ---
    private static final List<Entry<?>> AUTHORING = List.of();

    // --- lane:forge ---
    public static final Entry<GlyphDoc> GLYPHS =
        new Entry<>(GlyphDoc.KIND, List.of(GlyphDoc.DEFAULT_ID), GlyphDoc::parse);

    private static final List<Entry<?>> FORGE = List.of(GLYPHS);

    // --- lane:connections ---
    public static final Entry<ConnectionsDoc> CONNECTIONS =
        new Entry<>(ConnectionsDoc.KIND, List.of(ConnectionsDoc.KIND), ConnectionsDoc::parse);

    private static final List<Entry<?>> CONNECTIONS_LANE = List.of(CONNECTIONS);

    // --- lane:fixes ---
    private static final List<Entry<?>> FIXES = List.of();

    public static List<Entry<?>> all() {
        List<Entry<?>> all = new ArrayList<>(CORE);
        all.addAll(SCREEN);
        all.addAll(CHAT);
        all.addAll(FORMS);
        all.addAll(WORLD);
        all.addAll(BEHAVIORS);
        all.addAll(AUTHORING);
        all.addAll(FORGE);
        all.addAll(CONNECTIONS_LANE);
        all.addAll(FIXES);
        return List.copyOf(all);
    }
}
