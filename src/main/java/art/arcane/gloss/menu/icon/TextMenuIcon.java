package art.arcane.gloss.menu.icon;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.gloss.util.common.math.CollisionPlane;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TextMenuIcon extends MenuIcon<TextIconData> {

  private final List<Component> components;
  private int refreshInterval;
  /**
   * The post-pipeline string each line was last parsed from. MiniMessage parsing is deterministic,
   * so an unchanged string re-parses to an equal Component — comparing the strings first lets a
   * refresh whose placeholders did not move skip the parse entirely.
   */
  private List<String> parsedFrom;
  private String sourceText;
  private boolean dynamicSource;
  private boolean refreshFailureLogged;
  private int refreshCountdown;

  public TextMenuIcon(MenuSession session, Location loc, TextIconData data) throws MenuIconException {
    super(session, loc, data);
    sourceText = data.text();
    dynamicSource = TextPipeline.viewerDependent(sourceText);
    parsedFrom = new ArrayList<>();
    components = new ArrayList<>();
    components.addAll(render(sourceText));
    refreshInterval = refreshInterval(data, sourceText, GlossConfig.current().text().functions());
    refreshCountdown = refreshInterval;
  }

  @Override
  protected List<UUID> createDisplayEntities(Location loc) {
    List<UUID> uuids = new ArrayList<>(components.size());
    Location lineLocation = session.getTransform().localPosition(
        loc,
        new Vector(0F, ((components.size() - 1) / 2F * localLineHeight()) - localLineHeight(), 0F)
    );
    components.forEach(c -> {
      uuids.add(DisplayEntityManager.add(textDisplay(c, lineLocation)));
      lineLocation.add(session.getTransform().localVector(new Vector(0F, -localLineHeight(), 0F)));
    });
    return uuids;
  }

  @Override
  public CollisionPlane createBoundingBox(Location anchor) {
    float lineHeight = scaledLineHeight();
    float characterWidth = scaledCharacterWidth();
    float width = 0;
    for (Component component : components)
      width = Math.max(width, TextUtils.content(component).length() * characterWidth / 2F);
    return session.getTransform().createPlane(textBoundingBoxCenter(anchor), width, components.size() * lineHeight);
  }

  @Override
  public void tick() {
    if (refreshInterval == 0 || !dynamicSource) {
      return;
    }
    refreshCountdown--;
    if (refreshCountdown > 0) {
      return;
    }
    refreshCountdown = refreshInterval;
    try {
      updateText(sourceText);
      refreshFailureLogged = false;
    } catch (RuntimeException failure) {
      if (!refreshFailureLogged) {
        refreshFailureLogged = true;
        Gloss.logExceptionStack(false, failure,
            "Failed to refresh text placeholders in menu %s for %s; retaining the previous text.",
            session.getId(), session.getPlayer().getName());
      }
    }
  }

  public void updateName(int index, Component c) {
    if (index >= components.size())
      return;
    components.set(index, c);
    DisplayEntityManager.changeName(this.displayEntities.get(index), c);
  }

  public boolean updateText(String text) {
    sourceText = text;
    dynamicSource = TextPipeline.viewerDependent(sourceText);
    refreshInterval = refreshInterval(data, sourceText, GlossConfig.current().text().functions());
    refreshCountdown = refreshInterval;
    if (displayEntities == null || displayEntities.size() != components.size())
      return false;

    List<Component> replacement = render(text);
    if (replacement.equals(components)) {
      return true;
    }

    if (replacement.size() != components.size()) {
      remove();
      components.clear();
      components.addAll(replacement);
      spawn();
      markGeometryChanged();
      return true;
    }

    for (int index = 0; index < replacement.size(); index++) {
      if (replacement.get(index).equals(components.get(index)))
        continue;
      updateName(index, replacement.get(index));
    }

    markGeometryChanged();
    return true;
  }

  static int refreshInterval(TextIconData data, String text, boolean functionsEnabled) {
    if (data.refreshTicks() != null) {
      return data.refreshTicks();
    }
    return functionsEnabled && TextPipeline.requiresFastRefresh(text) ? 1 : TextIconData.DEFAULT_REFRESH_TICKS;
  }

  /**
   * Renders one component per line, reusing the previous line's component whenever the text that
   * produced it is unchanged. Updates the parse cache as a side effect.
   */
  private List<Component> render(String text) {
    Player player = session.getPlayer();
    String[] lines = (text == null ? "" : text).split("\n");
    List<Component> rendered = new ArrayList<>(lines.length);
    List<String> sources = new ArrayList<>(lines.length);
    int cached = Math.min(parsedFrom.size(), components.size());
    for (int index = 0; index < lines.length; index++) {
      String piped = TextPipeline.menuText(player, lines[index]);
      sources.add(piped);
      if (index < cached && piped.equals(parsedFrom.get(index))) {
        rendered.add(components.get(index));
      } else {
        rendered.add(TextUtils.parse(piped));
      }
    }
    parsedFrom = sources;
    return rendered;
  }

}
