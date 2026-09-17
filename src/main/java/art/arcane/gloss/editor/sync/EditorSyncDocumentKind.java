package art.arcane.gloss.editor.sync;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.behavior.BehaviorDoc;
import art.arcane.gloss.behavior.BehaviorService;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.chat.ChannelDoc;
import art.arcane.gloss.chat.ChannelService;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.connection.ConnectionsDoc;
import art.arcane.gloss.connection.ConnectionsService;
import art.arcane.gloss.dialog.DialogDoc;
import art.arcane.gloss.dialog.DialogService;
import art.arcane.gloss.inventory.InventoryDoc;
import art.arcane.gloss.inventory.InventoryMenuService;
import art.arcane.gloss.config.menu.MenuIds;
import art.arcane.gloss.doc.DocumentIds;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.forge.GlyphDoc;
import art.arcane.gloss.forge.GlyphService;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.gloss.leaderboard.LeaderboardDoc;
import art.arcane.gloss.leaderboard.LeaderboardService;
import art.arcane.gloss.marker.MarkerDoc;
import art.arcane.gloss.marker.MarkerService;
import art.arcane.gloss.nameplate.NameplateDoc;
import art.arcane.gloss.nameplate.NameplateService;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.waypoint.WaypointDoc;
import art.arcane.gloss.waypoint.WaypointService;
import art.arcane.gloss.zone.ZoneDoc;
import art.arcane.gloss.zone.ZoneService;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.motion.MotionDoc;
import art.arcane.gloss.motion.MotionService;
import art.arcane.gloss.nametag.NametagDoc;
import art.arcane.gloss.nametag.NametagService;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelIds;
import art.arcane.gloss.preview.doc.PreviewDocumentParser;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.rig.RigDoc;
import art.arcane.gloss.rig.RigInstanceDoc;
import art.arcane.gloss.rig.RigService;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.strings.StringsDoc;
import art.arcane.gloss.strings.StringsService;
import art.arcane.gloss.surface.SurfaceDoc;
import art.arcane.gloss.surface.SurfaceService;
import art.arcane.gloss.tab.TablistDoc;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Every document kind the editor sync protocol carries. A kind is one constant: wire name, storage
 * location, layout, whether the envelope is versioned, the singleton id (null for multi-document
 * kinds), how an id is canonicalized, how source text parses, and which runtime reloads after a
 * publication. New kinds append inside their lane anchor; nothing else in the sync package or in
 * {@link Gloss} needs a matching edit.
 */
public enum EditorSyncDocumentKind {
  ANIMATION("animation", "animations", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> AnimationDoc.parse(id + ".json", source),
      gloss -> gloss.animations().reload()),
  BUBBLE_STYLE("bubble-style", "bubbles", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> BubbleStyleDoc.parse(id + ".json", source),
      gloss -> gloss.bubbles().reload()),
  CONTAINER_PREVIEW("container-preview", "previews", Layout.FOLDER, false, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> PreviewDocumentParser.parse(id, source),
      gloss -> {
        PreviewDocumentRegistry registry = gloss.getPreviewRegistry();
        if (registry != null) {
          registry.reload();
        }
      }),
  DAMAGE_INDICATORS("damage-indicators", "damage-indicators", Layout.FOLDER, true,
      DamageIndicatorSettingsDoc.DEFAULT_ID,
      value -> requireSingleton(value, DamageIndicatorSettingsDoc.DEFAULT_ID),
      (id, source) -> DamageIndicatorSettingsDoc.parse(id + ".json", source),
      gloss -> gloss.indicators().reloadSettings()),
  EMOJI("emoji", "emoji", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> EmojiDoc.parse(id + ".json", source),
      gloss -> gloss.emoji().reload()),
  ENTITY_OVERLAYS("entity-overlays", "entity-overlays", Layout.FOLDER, true, EntityOverlayDoc.DEFAULT_ID,
      value -> requireSingleton(value, EntityOverlayDoc.DEFAULT_ID),
      (id, source) -> EntityOverlayDoc.parse(id + ".json", source),
      gloss -> gloss.entityOverlays().reload()),
  HOLOGRAM("hologram", "holograms", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> HologramDoc.parse(id + ".json", source),
      gloss -> gloss.holograms().reload()),
  MENU("menu", "menus", Layout.TREE, false, null,
      MenuIds::require,
      (id, source) -> MenuDocumentParser.parse(id, source),
      gloss -> {
      }),
  MOTD("motd", "motd.json", Layout.SINGLE, true, "motd",
      value -> requireSingleton(value, "motd"),
      (id, source) -> MotdDoc.parse("motd.json", source),
      gloss -> gloss.motd().reload()),
  PANEL("panel", "panels", Layout.TREE, true, null,
      PanelIds::canonicalize,
      EditorSyncDocumentKind::parsePanel,
      gloss -> gloss.getPanelService().publishExternalReload()),
  REAL_DROPS("real-drops", "real-drops", Layout.REAL_DROPS, true, RealDropSettingsDoc.DEFAULT_ID,
      value -> requireSingleton(value, RealDropSettingsDoc.DEFAULT_ID),
      (id, source) -> RealDropSettingsDoc.parse("default.json", source),
      gloss -> gloss.drops().reload()),
  SCOREBOARD("scoreboard", "boards", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> BoardDoc.parse(id + ".json", source),
      gloss -> gloss.boards().reload()),
  TABLIST("tablist", "tablist.json", Layout.SINGLE, true, "tablist",
      value -> requireSingleton(value, "tablist"),
      (id, source) -> TablistDoc.parse("tablist.json", source),
      gloss -> gloss.tablist().reload()),
  // --- lane:screen ---
  SURFACE("surface", "surfaces", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> SurfaceDoc.parse(id + ".json", source),
      gloss -> gloss.service(SurfaceService.class).reload()),
  NAMETAG("nametag", "nametags", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> NametagDoc.parse(id + ".json", source),
      gloss -> gloss.service(NametagService.class).reload()),

  // --- lane:chat ---
  CHANNEL("channel", "channels", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> ChannelDoc.parse(id + ".json", source),
      gloss -> reloadService(gloss, ChannelService.class)),
  STRINGS("strings", "strings", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> StringsDoc.parse(id + ".json", source),
      gloss -> reloadService(gloss, StringsService.class)),
  LEADERBOARD("leaderboard", "leaderboards", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> LeaderboardDoc.parse(id + ".json", source),
      gloss -> reloadService(gloss, LeaderboardService.class)),

  // --- lane:forms ---
  DIALOG("dialog", "dialogs", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> DialogDoc.parse(id + ".json", source),
      gloss -> {
        DialogService dialogs = gloss.service(DialogService.class);
        if (dialogs != null) {
          dialogs.reload();
        }
      }),
  INVENTORY("inventory", "inventories", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> InventoryDoc.parse(id + ".json", source),
      gloss -> {
        InventoryMenuService inventories = gloss.service(InventoryMenuService.class);
        if (inventories != null) {
          inventories.reload();
        }
      }),

  // --- lane:rigs ---
  MOTION("motion", "motion", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> MotionDoc.parse(id + ".json", source),
      gloss -> reloadService(gloss, MotionService.class)),
  RIG("rig", "rigs", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> RigDoc.parse(id + ".json", source),
      gloss -> reloadService(gloss, RigService.class)),
  RIG_INSTANCE("rig-instance", "rig-instances", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> RigInstanceDoc.parse(id + ".json", source),
      gloss -> reloadService(gloss, RigService.class)),

  // --- lane:world ---
  MARKER("marker", "markers", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> MarkerDoc.parse(id + ".json", source),
      gloss -> reloadLaneService(gloss, MarkerService.class)),
  NAMEPLATE("nameplate", "nameplates", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> NameplateDoc.parse(id + ".json", source),
      gloss -> reloadLaneService(gloss, NameplateService.class)),
  WAYPOINT("waypoint", "waypoints", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> WaypointDoc.parse(id + ".json", source),
      gloss -> reloadLaneService(gloss, WaypointService.class)),
  ZONE("zone", "zones", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> ZoneDoc.parse(id + ".json", source),
      gloss -> reloadLaneService(gloss, ZoneService.class)),

  // --- lane:behaviors ---
  BEHAVIOR("behavior", "behaviors", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> BehaviorDoc.parse(id + ".json", source),
      gloss -> reloadService(gloss, BehaviorService.class)),

  // --- lane:authoring ---

  // --- lane:forge ---
  GLYPH("glyph", "glyphs", Layout.FOLDER, true, null,
      EditorSyncDocumentKind::requireFlatId,
      (id, source) -> GlyphDoc.parse(id + ".json", source),
      gloss -> {
        GlyphService forge = gloss.service(GlyphService.class);
        if (forge != null) {
          forge.reload();
        }
      }),

  // --- lane:connections ---
  CONNECTIONS("connections", "connections.json", Layout.SINGLE, true, ConnectionsDoc.KIND,
      value -> requireSingleton(value, ConnectionsDoc.KIND),
      (id, source) -> ConnectionsDoc.parse("connections.json", source),
      gloss -> reloadLaneService(gloss, ConnectionsService.class)),

  // --- lane:fixes ---

  ;

  public static final List<EditorSyncDocumentKind> ORDERED = Arrays.stream(values())
      .sorted(Comparator.comparing(EditorSyncDocumentKind::wireName))
      .toList();
  public static final List<String> ORDERED_WIRE_NAMES = ORDERED.stream()
      .map(EditorSyncDocumentKind::wireName)
      .toList();
  private static final Gson PERSISTED_GSON = new GsonBuilder()
      .serializeNulls()
      .disableHtmlEscaping()
      .setPrettyPrinting()
      .create();

  private final String wireName;
  private final String storageName;
  private final Layout layout;
  private final boolean versioned;
  private final String singletonId;
  private final UnaryOperator<String> canonicalizer;
  private final SourceParser parser;
  private final ReloadHook reloadHook;

  EditorSyncDocumentKind(String wireName, String storageName, Layout layout, boolean versioned,
                         String singletonId, UnaryOperator<String> canonicalizer, SourceParser parser,
                         ReloadHook reloadHook) {
    this.wireName = wireName;
    this.storageName = storageName;
    this.layout = layout;
    this.versioned = versioned;
    this.singletonId = singletonId;
    this.canonicalizer = canonicalizer;
    this.parser = parser;
    this.reloadHook = reloadHook;
  }

  public String wireName() {
    return wireName;
  }

  /** The folder name, or the file name for a {@link Layout#SINGLE} kind, beneath the data folder. */
  public String storageName() {
    return storageName;
  }

  public Layout layout() {
    return layout;
  }

  boolean versioned() {
    return versioned;
  }

  /** The only legal id when the kind is a singleton, otherwise null. */
  public String singletonId() {
    return singletonId;
  }

  /** Re-reads this kind's runtime after an editor publication wrote its files. */
  public void reload(Gloss plugin) throws IOException {
    reloadHook.reload(plugin);
  }

  /** The kind stored under the data-folder entry {@code collection}, or null when no kind owns it. */
  public static EditorSyncDocumentKind byStorageCollection(String collection) {
    for (EditorSyncDocumentKind kind : values()) {
      if (kind.storageName.equals(collection)) {
        return kind;
      }
    }
    return null;
  }

  public String canonicalId(String value) {
    String canonical = canonicalizer.apply(value);
    if (!canonical.equals(value)) {
      throw new IllegalArgumentException(wireName + " id is not canonical: " + value);
    }
    return canonical;
  }

  /** The data-folder file one document of this kind lives in. */
  public Path path(Path dataDirectory, String id) {
    String canonicalId = canonicalId(id);
    Path normalizedData = dataDirectory.toAbsolutePath().normalize();
    Path target = switch (layout) {
      case SINGLE -> normalizedData.resolve(storageName);
      case FOLDER, TREE, REAL_DROPS -> normalizedData.resolve(storageName)
          .resolve(canonicalId + ".json");
    };
    Path normalizedTarget = target.normalize();
    if (!normalizedTarget.startsWith(normalizedData) || normalizedTarget.equals(normalizedData)) {
      throw new IllegalArgumentException("sync document target escapes the data directory");
    }
    return normalizedTarget;
  }

  /** Parses source text into this kind's document, refusing anything it cannot read. */
  public ParsedDocument parse(String id, String source) {
    String canonicalId = canonicalId(id);
    Objects.requireNonNull(source, "source");
    Object value = parser.parse(canonicalId, source);
    Long revision = versioned ? requireRevision(source) : null;
    return new ParsedDocument(value, revision);
  }

  String wireSource(String id, String source, ParsedDocument parsed) {
    if (this != PANEL) {
      return source;
    }
    return EditorSyncJson.canonical(DocumentParsers.GSON.toJsonTree(parsed.value()));
  }

  byte[] persistedBytes(String id, String wireSource) {
    if (this != PANEL) {
      return wireSource.getBytes(StandardCharsets.UTF_8);
    }
    PanelDefinition panel = (PanelDefinition) parse(id, wireSource).value();
    return (PERSISTED_GSON.toJson(panel) + System.lineSeparator())
        .getBytes(StandardCharsets.UTF_8);
  }

  static EditorSyncDocumentKind parseWireName(String value) {
    for (EditorSyncDocumentKind kind : values()) {
      if (kind.wireName.equals(value)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unsupported Gloss sync document kind: " + value);
  }

  static EditorSyncDocumentKind forSubject(EditorSyncKind kind) {
    if (kind == EditorSyncKind.WORKSPACE) {
      throw new IllegalArgumentException("workspace is not a document kind");
    }
    return parseWireName(kind.wireName());
  }

  /** Lane services reload through the registry {@link Gloss} holds, or no-op before they enable. */
  private static void reloadService(Gloss gloss, Class<? extends GlossService> type) {
    GlossService service = gloss.service(type);
    if (service != null) {
      service.reload();
    }
  }

  private static PanelDefinition parsePanel(String id, String source) {
    PanelDefinition panel;
    try {
      panel = BukkitJson.GSON.fromJson(source, PanelDefinition.class);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException(id + ".json " + safeMessage(failure), failure);
    }
    if (panel == null || !panel.id().equals(id)) {
      throw new IllegalArgumentException("panel file id does not match document id: " + id);
    }
    return panel;
  }

  private static Long requireRevision(String source) {
    JsonElement parsed = JsonParser.parseString(source);
    if (!parsed.isJsonObject()) {
      throw new IllegalArgumentException("versioned sync document must be a JSON object");
    }
    JsonObject object = parsed.getAsJsonObject();
    long revision = EditorSyncJson.requireSafeLong(object, "revision");
    if (revision < 1L) {
      throw new IllegalArgumentException("sync document revision must be positive");
    }
    return revision;
  }

  /** Reloads a lane service that may not be installed, so a kind stays syncable either way. */
  private static <T extends GlossService> void reloadLaneService(Gloss gloss, Class<T> type) {
    T service = gloss.service(type);
    if (service != null) {
      service.reload();
    }
  }

  static String requireFlatId(String value) {
    String id = DocumentIds.require("document id", value);
    if (id.indexOf('/') >= 0) {
      throw new IllegalArgumentException("document id must not contain path separators: " + value);
    }
    return id;
  }

  static String requireSingleton(String value, String expected) {
    if (!expected.equals(value)) {
      throw new IllegalArgumentException("document id must be " + expected);
    }
    return expected;
  }

  private static String safeMessage(Throwable failure) {
    Throwable current = failure;
    String message = null;
    while (current != null) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        message = current.getMessage();
      }
      current = current.getCause();
    }
    return message == null ? failure.getClass().getSimpleName() : message;
  }

  public enum Layout {
    FOLDER,
    TREE,
    SINGLE,
    REAL_DROPS
  }

  /** Parses canonical-id-addressed source text into the kind's document object. */
  @FunctionalInterface
  public interface SourceParser {
    Object parse(String canonicalId, String source);
  }

  @FunctionalInterface
  public interface ReloadHook {
    void reload(Gloss plugin) throws IOException;
  }

  public record ParsedDocument(Object value, Long revision) {
    public ParsedDocument {
      value = Objects.requireNonNull(value, "value");
    }
  }
}
