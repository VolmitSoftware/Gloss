package art.arcane.gloss.menu.icon;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.icon.PlayerHeadIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.profile.PlayerHeadItems;
import art.arcane.gloss.profile.PlayerHeadLookup;
import art.arcane.gloss.profile.PlayerHeadProfile;
import art.arcane.gloss.profile.PlayerHeadService;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A head, drawn as the item display an item icon already uses.
 *
 * <p>It extends {@link ItemMenuIcon} rather than reimplementing one, so the anchor, the block
 * offset, the click plane and the billboard orientation are the same code an {@code item} icon
 * runs — the only thing this class adds is which stack sits on the display and when that changes.
 *
 * <p>The name goes through {@link TextPipeline}, so the head is per viewer whenever the source is:
 * a literal name resolves once and is done, a placeholder is re-read every
 * {@link PlayerHeadIconData#resolvedRefreshTicks()} ticks and each viewer of the same menu file
 * sees their own head.
 */
public final class PlayerHeadMenuIcon extends ItemMenuIcon {

  /**
   * The self-referencing tokens Gloss answers itself. {@link TextPipeline} routes {@code %...%}
   * through PlaceholderAPI, which is optional (README.md:19), so without it {@code %player_name%}
   * would arrive at the profile service still wrapped in percent signs and resolve to the unknown
   * head. Answering these three here makes "this viewer's head" work on a bare server, and
   * everything else still goes through the pipeline unchanged.
   */
  private static final Set<String> VIEWER_TOKENS = Set.of("%player_name%", "%player%", "{{player.name}}");

  private final PlayerHeadIconData head;
  private final int refreshInterval;
  private final boolean dynamicSource;

  private String renderedName;
  private PlayerHeadLookup.State renderedState;
  private PlayerHeadProfile renderedProfile;
  private int refreshCountdown;

  public PlayerHeadMenuIcon(MenuSession session, Location loc, PlayerHeadIconData data) throws MenuIconException {
    this(session, loc, data, render(session.getPlayer(), data));
  }

  private PlayerHeadMenuIcon(MenuSession session, Location loc, PlayerHeadIconData data, Render render)
      throws MenuIconException {
    super(session, loc, data, render.stack());
    this.head = data;
    this.renderedName = render.name();
    this.renderedState = render.state();
    this.renderedProfile = render.profile();
    this.refreshInterval = data.resolvedRefreshTicks();
    this.refreshCountdown = refreshInterval;
    this.dynamicSource = TextPipeline.viewerDependent(data.player()) || isViewerToken(data.player());
  }

  /**
   * Resolves the authored string to the username to look up.
   *
   * <p>Static so the mapping can be pinned on its own, and public because it is the contract an
   * author is relying on when they type a placeholder into {@code player}.
   *
   * @return the username to look up. May still be an unresolved placeholder when nothing could
   *         resolve it, which {@link PlayerHeadService#isResolvableName(String)} then rejects
   *         without spending a request
   */
  public static String viewerName(Player viewer, String source) {
    if (source == null) {
      return null;
    }
    String trimmed = source.trim();
    if (viewer != null && isViewerToken(trimmed)) {
      return viewer.getName();
    }
    String piped = TextPipeline.menuText(viewer, trimmed);
    return piped == null ? trimmed : piped.trim();
  }

  private static boolean isViewerToken(String source) {
    if (source == null) {
      return false;
    }
    return VIEWER_TOKENS.contains(source.trim().toLowerCase(Locale.ROOT).replace(" ", ""));
  }

  @Override
  public void tick() {
    if (refreshInterval == 0) {
      return;
    }
    // A literal name that already resolved cannot change; anything else is either viewer dependent
    // or still waiting on a lookup that a later tick can pick up.
    if (!dynamicSource && renderedState == PlayerHeadLookup.State.RESOLVED) {
      return;
    }
    refreshCountdown--;
    if (refreshCountdown > 0) {
      return;
    }
    refreshCountdown = refreshInterval;
    refresh();
  }

  private void refresh() {
    String name = viewerName(session.getPlayer(), head.player());
    PlayerHeadLookup lookup = lookupFor(name);
    if (Objects.equals(name, renderedName)
        && lookup.state() == renderedState
        && Objects.equals(lookup.profile(), renderedProfile)) {
      return;
    }
    renderedName = name;
    renderedState = lookup.state();
    renderedProfile = lookup.profile();
    replaceItem(stackFor(lookup));
  }

  /** The username this icon last rendered a head for. Test and debug seam. */
  public String renderedName() {
    return renderedName;
  }

  /** What the last lookup said. Test and debug seam. */
  public PlayerHeadLookup.State renderedState() {
    return renderedState;
  }

  private static Render render(Player viewer, PlayerHeadIconData data) throws MenuIconException {
    String name = viewerName(viewer, data.requirePlayer());
    PlayerHeadLookup lookup = lookupFor(name);
    return new Render(name, lookup.state(), lookup.profile(), stackFor(lookup));
  }

  /**
   * What this name resolves to right now, for a viewer that already had its placeholders applied.
   *
   * <p>Never throws and never blocks. With head resolution switched off, or before the service
   * exists, every name is UNKNOWN and every head is the signposted fallback — which is the whole
   * point of the switch: no outbound request, and an obvious on-screen answer for why.
   */
  public static PlayerHeadLookup lookupFor(String name) {
    if (!GlossConfig.current().playerHeads().enabled()) {
      return PlayerHeadLookup.unknown();
    }
    PlayerHeadService service = PlayerHeadService.active();
    return service == null ? PlayerHeadLookup.unknown() : service.lookup(name);
  }

  /** The same answer {@link #lookupFor(String)} gives, for the string as the author typed it. */
  public static PlayerHeadLookup lookupFor(Player viewer, PlayerHeadIconData data) {
    try {
      return lookupFor(viewerName(viewer, data.requirePlayer()));
    } catch (MenuIconException blankName) {
      return PlayerHeadLookup.unknown();
    }
  }

  private static ItemStack stackFor(PlayerHeadLookup lookup) {
    return PlayerHeadItems.stackFor(lookup, GlossConfig.current().playerHeads().unknownFallbackItem());
  }

  private record Render(String name, PlayerHeadLookup.State state, PlayerHeadProfile profile, ItemStack stack) {
  }
}
