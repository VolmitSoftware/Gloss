package art.arcane.gloss.nameplate;

import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

class NameplateSourceTest {
    @Test
    void onlyPlayerTargetsAreWanted() {
        NameplateSource source = new NameplateSource(() -> true);

        Assertions.assertTrue(source.wants(player(Map.of())));
        Assertions.assertFalse(source.wants(zombie()));
    }

    @Test
    void nothingIsWantedWhileTheFeatureIsOff() {
        Assertions.assertFalse(new NameplateSource(() -> false).wants(player(Map.of())));
    }

    @Test
    void aSneakingSubjectIsHiddenWhenTheDocumentSaysSo() {
        NameplateSource source = new NameplateSource(() -> true);

        Assertions.assertFalse(source.visible(viewer(), player(Map.of("isSneaking", true)), true));
        Assertions.assertTrue(source.visible(viewer(), player(Map.of("isSneaking", true)), false));
    }

    @Test
    void anInvisibleSubjectIsAlwaysHidden() {
        Assertions.assertFalse(new NameplateSource(() -> true)
            .visible(viewer(), player(Map.of("isInvisible", true)), false));
    }

    @Test
    void aSpectatorSubjectIsAlwaysHidden() {
        Assertions.assertFalse(new NameplateSource(() -> true)
            .visible(viewer(), player(Map.of("getGameMode", GameMode.SPECTATOR)), false));
    }

    @Test
    void aSubjectTheViewerCannotSeeIsHidden() {
        Player hidden = player(Map.of());
        Player blindViewer = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "canSee" -> false;
                case "getUniqueId" -> UUID.randomUUID();
                case "getName" -> "viewer";
                default -> CharacterizationSupport.identity(proxy, method, args);
            });

        Assertions.assertFalse(new NameplateSource(() -> true).visible(blindViewer, hidden, false));
    }

    @Test
    void aViewerNeverSeesTheirOwnNameplate() {
        UUID id = UUID.randomUUID();
        Player self = player(Map.of("getUniqueId", id));

        Assertions.assertFalse(new NameplateSource(() -> true).visible(self, self, false));
    }

    private static Player viewer() {
        return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "canSee" -> true;
                case "getUniqueId" -> UUID.randomUUID();
                case "getName" -> "viewer";
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }

    private static Player player(Map<String, Object> overrides) {
        UUID id = (UUID) overrides.getOrDefault("getUniqueId", UUID.randomUUID());
        return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                if (overrides.containsKey(method.getName())) {
                    return overrides.get(method.getName());
                }
                return switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "subject";
                    case "isSneaking", "isInvisible", "isDead" -> false;
                    case "isValid" -> true;
                    case "getGameMode" -> GameMode.SURVIVAL;
                    default -> CharacterizationSupport.identity(proxy, method, args);
                };
            });
    }

    private static LivingEntity zombie() {
        return (Zombie) CharacterizationSupport.proxy(new Class<?>[]{Zombie.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> UUID.randomUUID();
                case "isValid" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }
}
