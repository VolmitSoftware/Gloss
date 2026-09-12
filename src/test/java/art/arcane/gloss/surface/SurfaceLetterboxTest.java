package art.arcane.gloss.surface;

import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSlot;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The letterbox is the camera lane's seam into the screen lane's title slot. These pin the two
 * things the two lanes could not agree on alone: the bars go through the shared compositor, and
 * they never outrank a scene's own title cue.
 */
class SurfaceLetterboxTest {
    private final List<Title> sent = new ArrayList<>();
    private final List<String> released = new ArrayList<>();

    @Test
    void showingTheBarsClaimsTheTitleSlotForTheLongestRideTheConfigAllows() {
        SurfaceLetterbox.apply(recording(), viewer(), true, 30);

        assertEquals(1, sent.size());
        Title title = sent.getFirst();
        assertEquals(SurfaceLetterbox.PURPOSE, title.purpose());
        assertEquals(600, title.stayTicks());
        assertTrue(title.title().contains("█"), title.title());
        assertEquals(title.title(), title.subtitle());
    }

    @Test
    void theBarsClaimAtAmbientSoAnAuthoredTitleCueTakesTheSlotFromThem() {
        SurfaceLetterbox.apply(recording(), viewer(), true, 30);

        assertEquals(HudPriority.AMBIENT, sent.getFirst().priority());
        assertTrue(sent.getFirst().priority() < SurfacePriorities.of("notice"),
            "a title action defaults to notice and must win the slot");
    }

    @Test
    void endingTheRideReleasesTheSlotRatherThanLeavingTheBarsUp() {
        SurfaceLetterbox.apply(recording(), viewer(), false, 30);

        assertEquals(List.of(SurfaceLetterbox.PURPOSE), released);
        assertEquals(List.of(), sent, "an empty title cannot win the slot back from the live claim");
    }

    private record Title(String purpose, int priority, String title, String subtitle, int stayTicks) {
    }

    private SurfaceDelivery recording() {
        return new SurfaceDelivery() {
            @Override
            public void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots,
                                  String text) {
                throw new UnsupportedOperationException("the letterbox never touches the action bar");
            }

            @Override
            public void clearActionBar(Player viewer, String purpose) {
                throw new UnsupportedOperationException("the letterbox never touches the action bar");
            }

            @Override
            public void clearTitle(Player viewer, String purpose) {
                released.add(purpose);
            }

            @Override
            public boolean bossBar(Player viewer, String laneId, int priority, String title, double progress,
                                   BarColor color, BarStyle style, long staleMillis) {
                throw new UnsupportedOperationException("the letterbox never touches the boss bar");
            }

            @Override
            public void hideBossBar(Player viewer, String laneId) {
                throw new UnsupportedOperationException("the letterbox never touches the boss bar");
            }

            @Override
            public void title(Player viewer, String purpose, int priority, String title, String subtitle,
                              int fadeInTicks, int stayTicks, int fadeOutTicks) {
                sent.add(new Title(purpose, priority, title, subtitle, stayTicks));
            }

            @Override
            public void forget(UUID viewerId) {
            }
        };
    }

    private static Player viewer() {
        UUID id = UUID.nameUUIDFromBytes("letterbox".getBytes());
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "rider";
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
