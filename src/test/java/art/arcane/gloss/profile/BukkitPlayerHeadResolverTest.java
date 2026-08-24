package art.arcane.gloss.profile;

import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitPlayerHeadResolverTest {

  @Test
  void anOnlinePlayerUsesTheLiveProfileWithoutAnOutboundUpdate() throws Exception {
    UUID playerId = UUID.randomUUID();
    Class<?> liveProfileType = Player.class.getMethod("getPlayerProfile").getReturnType();
    PlayerProfile liveProfile = (PlayerProfile) CharacterizationSupport.proxy(
        new Class<?>[]{liveProfileType},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "getUniqueId" -> playerId;
          case "getName" -> "OnlineUser";
          case "getTextures" -> null;
          case "update" -> throw new AssertionError("the live online profile must not be updated");
          default -> CharacterizationSupport.identity(proxy, method, arguments);
        });
    Player player = (Player) CharacterizationSupport.proxy(
        new Class<?>[]{Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
          case "getPlayerProfile" -> liveProfile;
          case "getUniqueId" -> playerId;
          case "getName" -> "OnlineUser";
          case "isOnline" -> true;
          default -> CharacterizationSupport.identity(proxy, method, arguments);
        });
    Server server = (Server) CharacterizationSupport.proxy(
        new Class<?>[]{Server.class}, (proxy, method, arguments) -> switch (method.getName()) {
          case "getPlayerExact" -> "OnlineUser".equals(arguments[0]) ? player : null;
          case "createPlayerProfile" -> throw new AssertionError("the live online profile must be reused");
          default -> CharacterizationSupport.identity(proxy, method, arguments);
        });

    synchronized (Bukkit.class) {
      Object previous = CharacterizationSupport.installServer(server);
      try {
        CompletableFuture<Optional<PlayerHeadProfile>> future = new BukkitPlayerHeadResolver().resolve("OnlineUser");

        assertTrue(future.isDone());
        PlayerHeadProfile resolved = future.join().orElseThrow();
        assertEquals(playerId, resolved.uniqueId());
        assertEquals("OnlineUser", resolved.name());
      } finally {
        CharacterizationSupport.restoreServer(previous);
      }
    }
  }
}
