package art.arcane.gloss.editor.sync;

import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.config.menu.MenuIds;
import art.arcane.gloss.doc.DocumentIds;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelIds;
import art.arcane.gloss.preview.doc.PreviewDocumentParser;
import art.arcane.gloss.tab.TablistDoc;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public enum EditorSyncDocumentKind {
  ANIMATION("animation", "animations", Layout.FOLDER, true),
  BUBBLE_STYLE("bubble-style", "bubbles", Layout.FOLDER, true),
  CONTAINER_PREVIEW("container-preview", "previews", Layout.FOLDER, false),
  DAMAGE_INDICATORS("damage-indicators", "damage-indicators", Layout.FOLDER, true),
  EMOJI("emoji", "emoji", Layout.FOLDER, true),
  HOLOGRAM("hologram", "holograms", Layout.FOLDER, true),
  MENU("menu", "menus", Layout.TREE, false),
  MOTD("motd", "motd.json", Layout.SINGLE, true),
  PANEL("panel", "panels", Layout.TREE, true),
  REAL_DROPS("real-drops", "real-drops", Layout.REAL_DROPS, true),
  SCOREBOARD("scoreboard", "boards", Layout.FOLDER, true),
  TABLIST("tablist", "tablist.json", Layout.SINGLE, true);

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

  EditorSyncDocumentKind(String wireName, String storageName, Layout layout, boolean versioned) {
    this.wireName = wireName;
    this.storageName = storageName;
    this.layout = layout;
    this.versioned = versioned;
  }

  public String wireName() {
    return wireName;
  }

  String storageName() {
    return storageName;
  }

  Layout layout() {
    return layout;
  }

  boolean versioned() {
    return versioned;
  }

  String canonicalId(String value) {
    String canonical = switch (this) {
      case MENU -> MenuIds.require(value);
      case PANEL -> PanelIds.canonicalize(value);
      case DAMAGE_INDICATORS -> requireSingleton(value, DamageIndicatorSettingsDoc.DEFAULT_ID);
      case MOTD -> requireSingleton(value, "motd");
      case TABLIST -> requireSingleton(value, "tablist");
      case REAL_DROPS -> requireSingleton(value, RealDropSettingsDoc.DEFAULT_ID);
      default -> requireFlatId(value);
    };
    if (!canonical.equals(value)) {
      throw new IllegalArgumentException(wireName + " id is not canonical: " + value);
    }
    return canonical;
  }

  Path path(Path dataDirectory, String id) {
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

  ParsedDocument parse(String id, String source) {
    String canonicalId = canonicalId(id);
    Objects.requireNonNull(source, "source");
    Object value = switch (this) {
      case ANIMATION -> AnimationDoc.parse(canonicalId + ".json", source);
      case BUBBLE_STYLE -> BubbleStyleDoc.parse(canonicalId + ".json", source);
      case CONTAINER_PREVIEW -> PreviewDocumentParser.parse(canonicalId, source);
      case DAMAGE_INDICATORS -> DamageIndicatorSettingsDoc.parse(canonicalId + ".json", source);
      case EMOJI -> EmojiDoc.parse(canonicalId + ".json", source);
      case HOLOGRAM -> HologramDoc.parse(canonicalId + ".json", source);
      case MENU -> MenuDocumentParser.parse(canonicalId, source);
      case MOTD -> MotdDoc.parse("motd.json", source);
      case PANEL -> parsePanel(canonicalId, source);
      case REAL_DROPS -> RealDropSettingsDoc.parse("default.json", source);
      case SCOREBOARD -> BoardDoc.parse(canonicalId + ".json", source);
      case TABLIST -> TablistDoc.parse("tablist.json", source);
    };
    Long revision = versioned ? requireRevision(source) : null;
    return new ParsedDocument(value, revision);
  }

  String wireSource(String id, String source, ParsedDocument parsed) {
    if (this != PANEL) {
      return source;
    }
    return EditorSyncJson.canonical(BukkitJson.GSON.toJsonTree(parsed.value()));
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

  private static String requireFlatId(String value) {
    String id = DocumentIds.require("document id", value);
    if (id.indexOf('/') >= 0) {
      throw new IllegalArgumentException("document id must not contain path separators: " + value);
    }
    return id;
  }

  private static String requireSingleton(String value, String expected) {
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

  enum Layout {
    FOLDER,
    TREE,
    SINGLE,
    REAL_DROPS
  }

  record ParsedDocument(Object value, Long revision) {
    ParsedDocument {
      value = Objects.requireNonNull(value, "value");
    }
  }
}
