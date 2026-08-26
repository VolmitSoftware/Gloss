package art.arcane.gloss.menu.icon;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.icon.PlayerHeadIconData;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.profile.PlayerHeadItems;
import art.arcane.gloss.profile.PlayerHeadLookup;
import art.arcane.gloss.profile.PlayerHeadProfile;
import art.arcane.gloss.profile.PlayerHeadResolver;
import art.arcane.gloss.profile.PlayerHeadService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a player head resolves to, and what it draws when it does not.
 *
 * <p>Two seams are pinned here rather than a spawned icon: {@code lookupFor} — the name a viewer
 * ends up looking up and the answer it gets — and {@code PlayerHeadItems.materialFor} — which head
 * that answer draws. An assembled {@code ItemStack} is deliberately not, because
 * {@code Material.asItemType} initializes {@code org.bukkit.Registry}, which needs a real Paper
 * runtime (the same reason {@code CustomItemIntegrationTest} keeps its distance from
 * {@code Bukkit.setServer}). Everything between the two seams is the three-line switch in
 * {@code PlayerHeadMenuIcon.stackFor}.
 */
public class PlayerHeadMenuIconTest {

  private Object previousServer;
  private Gloss previousGloss;
  private Gloss gloss;

  @Before
  public void installHeadlessServer() throws ReflectiveOperationException {
    Server server = CharacterizationSupport.server(Map.of());
    previousServer = CharacterizationSupport.installServer(server);
    gloss = CharacterizationSupport.bareGloss(server);
    previousGloss = CharacterizationSupport.installGloss(gloss);
  }

  @After
  public void restore() throws ReflectiveOperationException {
    CharacterizationSupport.restoreGloss(previousGloss);
    CharacterizationSupport.restoreServer(previousServer);
  }

  // ---------------------------------------------------------------------
  // Placeholder resolution
  // ---------------------------------------------------------------------

  @Test
  public void theViewerTokensResolveToTheViewerWithoutPlaceholderApi() {
    Player viewer = player("Notch");

    assertEquals("Notch", PlayerHeadMenuIcon.viewerName(viewer, "%player_name%"));
    assertEquals("Notch", PlayerHeadMenuIcon.viewerName(viewer, "%player%"));
    assertEquals("Notch", PlayerHeadMenuIcon.viewerName(viewer, "{{ player.name }}"));
    assertEquals("Notch", PlayerHeadMenuIcon.viewerName(viewer, "  %PLAYER_NAME%  "));
  }

  @Test
  public void twoViewersOfTheSameIconGetTheirOwnName() {
    PlayerHeadIconData data = new PlayerHeadIconData("%player_name%", null, null);

    assertEquals("Notch", PlayerHeadMenuIcon.viewerName(player("Notch"), data.player()));
    assertEquals("jeb_", PlayerHeadMenuIcon.viewerName(player("jeb_"), data.player()));
  }

  @Test
  public void aLiteralNameIsLeftExactlyAsAuthoredOnceTrimmed() {
    assertEquals("Notch", PlayerHeadMenuIcon.viewerName(player("Someone"), "  Notch  "));
  }

  @Test
  public void anUnknownPlaceholderSurvivesAsItselfAndCanNeverBeLookedUp() {
    String unresolved = PlayerHeadMenuIcon.viewerName(player("Notch"), "%some_other_plugin_name%");

    assertEquals("%some_other_plugin_name%", unresolved);
    assertFalse(PlayerHeadService.isResolvableName(unresolved));
  }

  // ---------------------------------------------------------------------
  // What each viewer's lookup answers
  // ---------------------------------------------------------------------

  @Test
  public void eachViewerOfOnePlaceholderIconResolvesTheirOwnProfile() throws Exception {
    installService(name -> CompletableFuture.completedFuture(Optional.of(profile(name))));
    PlayerHeadIconData data = new PlayerHeadIconData("%player_name%", null, null);

    PlayerHeadLookup notch = PlayerHeadMenuIcon.lookupFor(player("Notch"), data);
    PlayerHeadLookup jeb = PlayerHeadMenuIcon.lookupFor(player("jeb_"), data);

    assertTrue(notch.isResolved());
    assertTrue(jeb.isResolved());
    assertEquals("Notch", notch.profile().name());
    assertEquals("jeb_", jeb.profile().name());
  }

  @Test
  public void anUnresolvedPlaceholderNeverReachesTheResolver() throws Exception {
    AtomicLong calls = new AtomicLong();
    installService(name -> {
      calls.incrementAndGet();
      return CompletableFuture.completedFuture(Optional.of(profile(name)));
    });

    PlayerHeadLookup lookup = PlayerHeadMenuIcon.lookupFor(
        player("Notch"), new PlayerHeadIconData("%some_other_plugin_name%", null, null));

    assertEquals(PlayerHeadLookup.State.UNKNOWN, lookup.state());
    assertEquals(0L, calls.get());
  }

  @Test
  public void aBlankNameAnswersUnknownRatherThanEscapingAsAnException() throws Exception {
    installService(name -> CompletableFuture.completedFuture(Optional.of(profile(name))));

    assertEquals(PlayerHeadLookup.State.UNKNOWN,
        PlayerHeadMenuIcon.lookupFor(player("Notch"), new PlayerHeadIconData("  ", null, null)).state());
  }

  @Test
  public void withHeadResolutionOffNothingIsLookedUpAtAll() throws Exception {
    AtomicLong calls = new AtomicLong();
    installService(name -> {
      calls.incrementAndGet();
      return CompletableFuture.completedFuture(Optional.of(profile(name)));
    });
    CharacterizationSupport.setField(gloss, "config", withHeadsEnabled(false));

    PlayerHeadLookup lookup = PlayerHeadMenuIcon.lookupFor(
        player("Notch"), new PlayerHeadIconData("Notch", null, null));

    assertEquals(PlayerHeadLookup.State.UNKNOWN, lookup.state());
    assertEquals(0L, calls.get());
  }

  @Test
  public void beforeTheServiceExistsEveryHeadIsUnknownInsteadOfAnError() {
    assertEquals(PlayerHeadLookup.State.UNKNOWN,
        PlayerHeadMenuIcon.lookupFor(player("Notch"), new PlayerHeadIconData("Notch", null, null)).state());
  }

  @Test
  public void aPendingLookupBecomesResolvedOnALaterPassWithoutAnotherRequest() throws Exception {
    CompletableFuture<Optional<PlayerHeadProfile>> answer = new CompletableFuture<>();
    AtomicLong calls = new AtomicLong();
    installService(name -> {
      calls.incrementAndGet();
      return answer;
    });
    PlayerHeadIconData data = new PlayerHeadIconData("Notch", null, null);

    assertTrue(PlayerHeadMenuIcon.lookupFor(player("Viewer"), data).isPending());

    answer.complete(Optional.of(profile("Notch")));

    assertTrue(PlayerHeadMenuIcon.lookupFor(player("Viewer"), data).isResolved());
    assertEquals(1L, calls.get());
  }

  // ---------------------------------------------------------------------
  // What each answer draws
  // ---------------------------------------------------------------------

  @Test
  public void aResolvedOrPendingLookupDrawsAPlayerHead() {
    String fallback = GlossConfig.current().playerHeads().unknownFallbackItem();

    assertEquals(Material.PLAYER_HEAD, PlayerHeadItems.materialFor(PlayerHeadLookup.State.RESOLVED, fallback));
    assertEquals(Material.PLAYER_HEAD, PlayerHeadItems.materialFor(PlayerHeadLookup.State.PENDING, fallback));
  }

  @Test
  public void anUnknownLookupDegradesToTheSignpostedFallbackHead() {
    String fallback = GlossConfig.current().playerHeads().unknownFallbackItem();

    Material drawn = PlayerHeadItems.materialFor(PlayerHeadLookup.State.UNKNOWN, fallback);

    assertEquals(Material.SKELETON_SKULL, drawn);
    assertNotEquals(Material.PLAYER_HEAD, drawn);
  }

  @Test
  public void theFallbackIsConfigurableAndSurvivesAnythingTheOperatorTypes() {
    assertEquals(Material.WITHER_SKELETON_SKULL, PlayerHeadItems.fallbackMaterial("minecraft:wither_skeleton_skull"));
    assertEquals(Material.WITHER_SKELETON_SKULL, PlayerHeadItems.fallbackMaterial("WITHER_SKELETON_SKULL"));
    assertEquals(PlayerHeadItems.DEFAULT_UNKNOWN_FALLBACK, PlayerHeadItems.fallbackMaterial(null));
    assertEquals(PlayerHeadItems.DEFAULT_UNKNOWN_FALLBACK, PlayerHeadItems.fallbackMaterial("   "));
    assertEquals(PlayerHeadItems.DEFAULT_UNKNOWN_FALLBACK, PlayerHeadItems.fallbackMaterial("not:a:real:id"));
    assertEquals(PlayerHeadItems.DEFAULT_UNKNOWN_FALLBACK, PlayerHeadItems.fallbackMaterial("minecraft:nothing_here"));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private void installService(PlayerHeadResolver resolver) throws ReflectiveOperationException {
    CharacterizationSupport.setField(gloss, "playerHeads", new PlayerHeadService(
        resolver, new AtomicLong()::get, Duration.ofMinutes(360L), Duration.ofMinutes(10L), 64));
  }

  private static PlayerHeadProfile profile(String name) {
    return new PlayerHeadProfile(UUID.nameUUIDFromBytes(name.getBytes()), name, null);
  }

  private static GlossConfig withHeadsEnabled(boolean enabled) {
    GlossConfig defaults = GlossConfig.current();
    GlossConfig.PlayerHeads heads = defaults.playerHeads();
    return new GlossConfig(
        defaults.language(), defaults.metrics(), defaults.splashScreen(),
        defaults.holograms(), defaults.particles(), defaults.boards(), defaults.tablist(), defaults.emoji(), defaults.animations(),
        defaults.chat(), defaults.text(), defaults.bubbles(), defaults.indicators(), defaults.drops(),
        defaults.realDrops(), defaults.motd(), defaults.groups(), defaults.hotload(), defaults.commands(),
        defaults.menus(), defaults.panels(), defaults.previews(),
        defaults.editorSync(), defaults.debug(), defaults.customItems(),
        new GlossConfig.PlayerHeads(enabled, heads.cacheMinutes(), heads.unknownCacheMinutes(),
            heads.maxCachedProfiles(), heads.unknownFallbackItem()),
        defaults.integration());
  }

  private static Player player(String name) {
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> name;
          case "getUniqueId" -> UUID.nameUUIDFromBytes(name.getBytes());
          case "getLocation" -> new Location(null, 0D, 0D, 0D);
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[" + name + "]";
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
