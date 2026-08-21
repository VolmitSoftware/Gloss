package art.arcane.gloss.profile;

import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.net.URI;
import java.net.URL;
import java.util.Locale;

/**
 * Turns a {@link PlayerHeadLookup} into the stack a {@code playerHead} icon renders.
 *
 * <p>Split in two on purpose. {@link #materialFor} is the decision — which of the three heads a
 * lookup state means — and is pure, so it can be pinned without a server. {@link #stackFor} is the
 * Bukkit half, which needs a live registry to build an {@link ItemStack} at all.
 *
 * <p>Nothing here throws. A server that cannot build or apply a profile still gets a stack back,
 * just an unskinned one, because a menu that renders a blank head is far better than a menu that
 * fails to open.
 */
public final class PlayerHeadItems {

  /**
   * What a name that does not resolve becomes. A skeleton skull is head-shaped, so the component
   * keeps its size and place, and is unmistakably not a player, so nobody spends an afternoon
   * wondering why someone's face looks wrong.
   */
  public static final Material DEFAULT_UNKNOWN_FALLBACK = Material.SKELETON_SKULL;

  private PlayerHeadItems() {
  }

  /**
   * The block each lookup state draws.
   *
   * <p>A lookup still in flight draws the same unowned {@code PLAYER_HEAD} a resolved one does, so
   * when the answer lands the icon swaps a texture and nothing else — no size change, no respawn,
   * no visible pop.
   */
  public static Material materialFor(PlayerHeadLookup.State state, String configuredFallback) {
    return switch (state) {
      case RESOLVED, PENDING -> Material.PLAYER_HEAD;
      case UNKNOWN -> fallbackMaterial(configuredFallback);
    };
  }

  /** The stack for this lookup, with the owner applied when there is one to apply. */
  public static ItemStack stackFor(PlayerHeadLookup lookup, String configuredFallback) {
    PlayerHeadLookup answer = lookup == null ? PlayerHeadLookup.unknown() : lookup;
    ItemStack stack = new ItemStack(materialFor(answer.state(), configuredFallback));
    if (!answer.isResolved()) {
      return stack;
    }
    applyOwner(stack, answer.profile());
    return stack;
  }

  /**
   * Resolves the configured fallback id, accepting it with or without a namespace and ignoring
   * anything that is not a real block: the fallback is the last thing standing between a bad name
   * and a broken menu, so it never fails.
   */
  public static Material fallbackMaterial(String configured) {
    if (configured == null || configured.isBlank()) {
      return DEFAULT_UNKNOWN_FALLBACK;
    }
    Material resolved = lookup(configured.trim().toLowerCase(Locale.ROOT));
    if (resolved == null) {
      return DEFAULT_UNKNOWN_FALLBACK;
    }
    try {
      return resolved.isBlock() && !resolved.isAir() ? resolved : DEFAULT_UNKNOWN_FALLBACK;
    } catch (RuntimeException | LinkageError unavailableRegistry) {
      return resolved;
    }
  }

  private static Material lookup(String id) {
    try {
      NamespacedKey key = NamespacedKey.fromString(id);
      if (key == null) {
        return null;
      }
      return RegistryUtil.find(Material.class, key);
    } catch (RuntimeException | LinkageError unavailableRegistry) {
      String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
      return Material.getMaterial(name.toUpperCase(Locale.ROOT));
    }
  }

  private static void applyOwner(ItemStack stack, PlayerHeadProfile profile) {
    if (profile == null) {
      return;
    }
    try {
      ItemMeta meta = stack.getItemMeta();
      if (!(meta instanceof SkullMeta skull)) {
        return;
      }
      PlayerProfile owner = Bukkit.createPlayerProfile(profile.uniqueId(), profile.name());
      applySkin(owner, profile.skinUrl());
      skull.setOwnerProfile(owner);
      stack.setItemMeta(skull);
    } catch (RuntimeException | LinkageError unusableProfileApi) {
      // An unskinned head is still the right size, the right shape, and in the right place.
    }
  }

  /**
   * Sets the skin as an unsigned texture. The client fetches skull textures from the URL in the
   * profile property, so an unsigned one renders exactly like a signed one; the signature only
   * matters for a profile the client treats as an account.
   */
  private static void applySkin(PlayerProfile owner, String skinUrl) {
    if (skinUrl == null || skinUrl.isBlank()) {
      return;
    }
    try {
      URL url = URI.create(skinUrl).toURL();
      owner.getTextures().setSkin(url);
    } catch (RuntimeException | java.io.IOException unusableUrl) {
      // A resolved account whose skin URL we cannot parse still renders as that account's default
      // skin, which is closer to right than showing the unknown-name fallback.
    }
  }
}
