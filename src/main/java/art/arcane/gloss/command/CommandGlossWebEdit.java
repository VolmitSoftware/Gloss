package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.editor.sync.EditorSyncKind;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import org.bukkit.command.CommandSender;

@Director(name = "edit", description = "Open one live Gloss document",
    descriptionKey = "command.help.web.edit")
public final class CommandGlossWebEdit {
  private final CommandGlossWeb web;

  public CommandGlossWebEdit(CommandGlossWeb web) {
    this.web = web;
  }

  @Director(name = "menu", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void menu(@Param(name = "id", description = "Live Gloss document id",
                       descriptionKey = "command.help.arg.web_subject", customHandler = MenuIdHandler.class) String id,
                   @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("menu", id, sender);
  }

  @Director(name = "panel", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void panel(@Param(name = "id", description = "Live Gloss document id",
                        descriptionKey = "command.help.arg.web_subject", customHandler = PanelIdHandler.class) String id,
                    @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("panel", id, sender);
  }

  @Director(name = "hologram", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void hologram(@Param(name = "id", description = "Live Gloss document id",
                           descriptionKey = "command.help.arg.web_subject", customHandler = HologramIdHandler.class) String id,
                       @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("hologram", id, sender);
  }

  @Director(name = "scoreboard", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void scoreboard(@Param(name = "id", description = "Live Gloss document id",
                             descriptionKey = "command.help.arg.web_subject", customHandler = ScoreboardIdHandler.class) String id,
                         @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("scoreboard", id, sender);
  }

  @Director(name = "emoji", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void emoji(@Param(name = "id", description = "Live Gloss document id",
                        descriptionKey = "command.help.arg.web_subject", customHandler = EmojiIdHandler.class) String id,
                    @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("emoji", id, sender);
  }

  @Director(name = "animation", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void animation(@Param(name = "id", description = "Live Gloss document id",
                            descriptionKey = "command.help.arg.web_subject", customHandler = AnimationIdHandler.class) String id,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("animation", id, sender);
  }

  @Director(name = "bubble-style", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void bubbleStyle(@Param(name = "id", description = "Live Gloss document id",
                              descriptionKey = "command.help.arg.web_subject", customHandler = BubbleStyleIdHandler.class) String id,
                          @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("bubble-style", id, sender);
  }

  @Director(name = "container-preview", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void containerPreview(
      @Param(name = "id", description = "Live Gloss document id",
          descriptionKey = "command.help.arg.web_subject", customHandler = ContainerPreviewIdHandler.class) String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("container-preview", id, sender);
  }

  @Director(name = "tablist", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void tablist(@Param(name = "id", description = "Live Gloss document id",
                          descriptionKey = "command.help.arg.web_subject", customHandler = TablistIdHandler.class) String id,
                      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("tablist", id, sender);
  }

  @Director(name = "motd", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void motd(@Param(name = "id", description = "Live Gloss document id",
                       descriptionKey = "command.help.arg.web_subject", customHandler = MotdIdHandler.class) String id,
                   @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("motd", id, sender);
  }

  @Director(name = "real-drops", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void realDrops(@Param(name = "id", description = "Live Gloss document id",
                            descriptionKey = "command.help.arg.web_subject", customHandler = RealDropsIdHandler.class) String id,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("real-drops", id, sender);
  }

  @Director(name = "damage-indicators", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void damageIndicators(
      @Param(name = "id", description = "Live Gloss document id",
          descriptionKey = "command.help.arg.web_subject",
          customHandler = DamageIndicatorsIdHandler.class) String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("damage-indicators", id, sender);
  }

  @Director(name = "entity-overlays", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void entityOverlays(
      @Param(name = "id", description = "Live Gloss document id",
          descriptionKey = "command.help.arg.web_subject",
          customHandler = EntityOverlaysIdHandler.class) String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("entity-overlays", id, sender);
  }

  public static final class EntityOverlaysIdHandler extends SubjectIdHandler {
    public EntityOverlaysIdHandler() { super("entity-overlays"); }
  }

  // Lane web-edit surfaces: one @Director method per syncable kind, inside the lane's anchor.
  // --- lane:screen ---
  @Director(name = "surface", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void surface(
      @Param(name = "id", description = "Live Gloss document id",
          descriptionKey = "command.help.arg.web_subject",
          customHandler = SurfaceIdHandler.class) String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("surface", id, sender);
  }

  @Director(name = "nametag", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void nametag(
      @Param(name = "id", description = "Live Gloss document id",
          descriptionKey = "command.help.arg.web_subject",
          customHandler = NametagIdHandler.class) String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("nametag", id, sender);
  }

  // --- lane:chat ---
  @Director(name = "channel", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void channel(@Param(name = "id", description = "Live Gloss document id",
                          descriptionKey = "command.help.arg.web_subject",
                          customHandler = ChannelIdHandler.class) String id,
                      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("channel", id, sender);
  }

  @Director(name = "strings", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void strings(@Param(name = "id", description = "Live Gloss document id",
                          descriptionKey = "command.help.arg.web_subject",
                          customHandler = StringsIdHandler.class) String id,
                      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("strings", id, sender);
  }

  @Director(name = "leaderboard", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void leaderboard(@Param(name = "id", description = "Live Gloss document id",
                              descriptionKey = "command.help.arg.web_subject",
                              customHandler = LeaderboardIdHandler.class) String id,
                          @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("leaderboard", id, sender);
  }

  // --- lane:forms ---

  @Director(name = "inventory", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void inventory(@Param(name = "id", description = "Live Gloss document id",
                            descriptionKey = "command.help.arg.web_subject", customHandler = InventoryIdHandler.class) String id,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("inventory", id, sender);
  }

  // --- lane:world ---
  @Director(name = "marker", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void marker(@Param(name = "id", description = "Live Gloss document id",
                         descriptionKey = "command.help.arg.web_subject",
                         customHandler = MarkerIdHandler.class) String id,
                     @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("marker", id, sender);
  }

  @Director(name = "waypoint", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void waypoint(@Param(name = "id", description = "Live Gloss document id",
                           descriptionKey = "command.help.arg.web_subject",
                           customHandler = WaypointIdHandler.class) String id,
                       @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("waypoint", id, sender);
  }

  @Director(name = "nameplate", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void nameplate(@Param(name = "id", description = "Live Gloss document id",
                            descriptionKey = "command.help.arg.web_subject",
                            customHandler = NameplateIdHandler.class) String id,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("nameplate", id, sender);
  }

  // --- lane:behaviors ---
  @Director(name = "behavior", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void behavior(@Param(name = "id", description = "Live Gloss document id",
                           descriptionKey = "command.help.arg.web_subject",
                           customHandler = BehaviorIdHandler.class) String id,
                       @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("behavior", id, sender);
  }

  // --- lane:authoring ---

  // --- lane:forge ---
  @Director(name = "glyph", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void glyph(
      @Param(name = "id", description = "Live Gloss document id",
          descriptionKey = "command.help.arg.web_subject",
          customHandler = GlyphIdHandler.class) String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("glyph", id, sender);
  }

  // --- lane:connections ---
  @Director(name = "connections", description = "Open this live document in the web editor",
      descriptionKey = "command.help.web.edit.document")
  public void connections(
      @Param(name = "id", description = "Live Gloss document id",
          descriptionKey = "command.help.arg.web_subject",
          customHandler = ConnectionsIdHandler.class) String id,
      @Param(name = "sender", contextual = true) CommandSender sender) {
    web.openSubject("connections", id, sender);
  }

  // --- lane:fixes ---

  public abstract static class SubjectIdHandler implements DirectorParameterHandler<String> {
    private final String wireKind;

    protected SubjectIdHandler(String wireKind) {
      this.wireKind = wireKind;
    }

    @Override
    public KList<String> getPossibilities() {
      KList<String> ids = new KList<>();
      Gloss plugin = Gloss.instance;
      if (plugin == null || plugin.getEditorSyncService() == null) {
        return ids;
      }
      ids.addAll(plugin.getEditorSyncService().subjectIds(EditorSyncKind.parse(wireKind)));
      ids.removeDuplicates();
      return ids;
    }

    @Override
    public String toString(String value) {
      return value == null ? "" : value;
    }

    @Override
    public String parse(String in, boolean force) throws DirectorParsingException {
      if (in == null || in.isBlank()) {
        throw new DirectorParsingException(
            GlossLocalization.globalText(GlossMessages.ERROR_WEB_SUBJECT_REQUIRED));
      }
      return in.strip();
    }

    @Override
    public boolean supports(Class<?> type) {
      return type == String.class;
    }
  }

  public static final class MenuIdHandler extends SubjectIdHandler {
    public MenuIdHandler() { super("menu"); }
  }

  public static final class PanelIdHandler extends SubjectIdHandler {
    public PanelIdHandler() { super("panel"); }
  }

  public static final class HologramIdHandler extends SubjectIdHandler {
    public HologramIdHandler() { super("hologram"); }
  }

  public static final class ScoreboardIdHandler extends SubjectIdHandler {
    public ScoreboardIdHandler() { super("scoreboard"); }
  }

  public static final class EmojiIdHandler extends SubjectIdHandler {
    public EmojiIdHandler() { super("emoji"); }
  }

  public static final class AnimationIdHandler extends SubjectIdHandler {
    public AnimationIdHandler() { super("animation"); }
  }

  public static final class BubbleStyleIdHandler extends SubjectIdHandler {
    public BubbleStyleIdHandler() { super("bubble-style"); }
  }

  public static final class ContainerPreviewIdHandler extends SubjectIdHandler {
    public ContainerPreviewIdHandler() { super("container-preview"); }
  }

  public static final class TablistIdHandler extends SubjectIdHandler {
    public TablistIdHandler() { super("tablist"); }
  }

  public static final class MotdIdHandler extends SubjectIdHandler {
    public MotdIdHandler() { super("motd"); }
  }

  public static final class RealDropsIdHandler extends SubjectIdHandler {
    public RealDropsIdHandler() { super("real-drops"); }
  }

  public static final class DamageIndicatorsIdHandler extends SubjectIdHandler {
    public DamageIndicatorsIdHandler() { super("damage-indicators"); }
  }

  // Lane id handlers: one SubjectIdHandler subclass per syncable kind, inside the lane's anchor.
  // --- lane:screen ---
  public static final class SurfaceIdHandler extends SubjectIdHandler {
    public SurfaceIdHandler() { super("surface"); }
  }

  public static final class NametagIdHandler extends SubjectIdHandler {
    public NametagIdHandler() { super("nametag"); }
  }

  // --- lane:chat ---
  public static final class ChannelIdHandler extends SubjectIdHandler {
    public ChannelIdHandler() { super("channel"); }
  }

  public static final class StringsIdHandler extends SubjectIdHandler {
    public StringsIdHandler() { super("strings"); }
  }

  public static final class LeaderboardIdHandler extends SubjectIdHandler {
    public LeaderboardIdHandler() { super("leaderboard"); }
  }

  // --- lane:forms ---

  public static final class InventoryIdHandler extends SubjectIdHandler {
    public InventoryIdHandler() { super("inventory"); }
  }

  // --- lane:world ---
  public static final class MarkerIdHandler extends SubjectIdHandler {
    public MarkerIdHandler() { super("marker"); }
  }

  public static final class WaypointIdHandler extends SubjectIdHandler {
    public WaypointIdHandler() { super("waypoint"); }
  }

  public static final class NameplateIdHandler extends SubjectIdHandler {
    public NameplateIdHandler() { super("nameplate"); }
  }

  // --- lane:behaviors ---
  public static final class BehaviorIdHandler extends SubjectIdHandler {
    public BehaviorIdHandler() { super("behavior"); }
  }

  // --- lane:authoring ---

  // --- lane:forge ---
  public static final class GlyphIdHandler extends SubjectIdHandler {
    public GlyphIdHandler() { super("glyph"); }
  }

  // --- lane:connections ---
  public static final class ConnectionsIdHandler extends SubjectIdHandler {
    public ConnectionsIdHandler() { super("connections"); }
  }

  // --- lane:fixes ---

}
