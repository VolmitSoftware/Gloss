package art.arcane.gloss.menu.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.ActionBarActionData;
import art.arcane.gloss.config.action.BossBarActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.TitleActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.surface.SurfaceDelivery;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SurfaceActionsTest {
    @Test
    void aTitleActionParsesThroughTheActionAdapter() {
        TitleActionData data = (TitleActionData) action("{\"type\":\"title\",\"title\":\"Hi\"}");

        assertEquals(MenuActionType.TITLE, data.getType());
        assertEquals("Hi", data.title());
        assertNull(data.invalidReason());
        assertEquals(10, data.fadeInTicksOrDefault());
        assertEquals(40, data.stayTicksOrDefault());
        assertEquals(10, data.fadeOutTicksOrDefault());
        assertEquals(HudPriority.NOTICE, data.priorityValue());
        assertNotNull(data.createAction());
    }

    @Test
    void aBlankTitleIsReportedInsteadOfSilentlyDoingNothing() {
        assertEquals("declares an empty title", action("{\"type\":\"title\",\"title\":\"  \"}").invalidReason());
    }

    @Test
    void anActionBarActionParsesWithItsSlotsAndTicks() {
        ActionBarActionData data = (ActionBarActionData) action(
            "{\"type\":\"actionbar\",\"text\":\"Saved\",\"ticks\":60,\"slots\":[\"center\",\"right\"],\"priority\":\"status\"}");

        assertEquals(List.of(HudSlot.CENTER, HudSlot.RIGHT), data.hudSlots());
        assertEquals(60, data.ticksOrDefault());
        assertEquals(HudPriority.STATUS, data.priorityValue());
        assertNull(data.invalidReason());
    }

    @Test
    void aBlankActionBarTextIsReported() {
        assertEquals("declares an empty action bar text",
            action("{\"type\":\"actionbar\",\"text\":\"\"}").invalidReason());
    }

    @Test
    void aBossBarActionParsesWithItsColourStyleAndProgress() {
        BossBarActionData data = (BossBarActionData) action(
            "{\"type\":\"bossbar\",\"id\":\"quest\",\"title\":\"Quest\",\"progress\":\"0.4\",\"color\":\"green\",\"style\":\"solid\",\"ticks\":100}");

        assertEquals("quest", data.id());
        assertEquals(BarColor.GREEN, data.barColor());
        assertEquals(BarStyle.SOLID, data.barStyle());
        assertEquals(0.4D, data.progressValue(), 1.0E-9D);
        assertNull(data.invalidReason());
    }

    @Test
    void aBossBarActionWithoutAnIdIsReported() {
        assertEquals("declares an empty boss bar id",
            action("{\"type\":\"bossbar\",\"title\":\"Quest\"}").invalidReason());
    }

    @Test
    void everySurfaceActionContinuesTheList() {
        RecordingDelivery delivery = new RecordingDelivery();
        Player player = player();
        ActionContext context = context(player);

        assertEquals(ActionOutcome.CONTINUE, new TitleMenuAction(
            (TitleActionData) action("{\"type\":\"title\",\"title\":\"Hi\",\"subtitle\":\"There\"}"), delivery)
            .execute(context));
        assertEquals(ActionOutcome.CONTINUE, new ActionBarMenuAction(
            (ActionBarActionData) action("{\"type\":\"actionbar\",\"text\":\"Saved\"}"), delivery).execute(context));
        assertEquals(ActionOutcome.CONTINUE, new BossBarMenuAction(
            (BossBarActionData) action("{\"type\":\"bossbar\",\"id\":\"quest\",\"title\":\"Quest\"}"), delivery)
            .execute(context));

        assertEquals(List.of(
            "title gloss:action:shop/buy " + HudPriority.NOTICE + " Hi There 10 40 10",
            "actionBar gloss:action:shop/buy " + HudPriority.NOTICE + " 3000 [CENTER] Saved",
            "bossBar gloss:action:shop/buy/quest " + HudPriority.NOTICE + " Quest 1.0 WHITE SOLID 5000"),
            delivery.calls);
    }

    @Test
    void aBossBarActionWithZeroTicksHidesTheLaneInstead() {
        RecordingDelivery delivery = new RecordingDelivery();
        BossBarActionData data = (BossBarActionData) action(
            "{\"type\":\"bossbar\",\"id\":\"quest\",\"title\":\"Quest\",\"ticks\":0}");

        assertEquals(ActionOutcome.CONTINUE, new BossBarMenuAction(data, delivery).execute(context(player())));
        assertEquals(List.of("hideBossBar gloss:action:shop/buy/quest"), delivery.calls);
    }

    private static MenuActionData action(String json) {
        return DocumentParsers.GSON.fromJson(json, MenuActionData.class);
    }

    private static ActionContext context(Player player) {
        return new ActionContext() {
            @Override
            public Player player() {
                return player;
            }

            @Override
            public String menuId() {
                return "shop";
            }

            @Override
            public String componentId() {
                return "buy";
            }

            @Override
            public HoloClickTrigger trigger() {
                return HoloClickTrigger.ANY;
            }

            @Override
            public NavigationResult navigate(NavigationRequest request) {
                return NavigationResult.DENIED;
            }

            @Override
            public ExprScope conditionScope() {
                return new ExprScope() {
                    @Override
                    public Object variable(String dottedName) {
                        return null;
                    }

                    @Override
                    public Object call(String name, List<Object> args) {
                        return null;
                    }
                };
            }
        };
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Viewer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static final class RecordingDelivery implements SurfaceDelivery {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots,
                              String text) {
            calls.add("actionBar " + purpose + " " + priority + " " + ttlMillis + " " + slots + " " + text);
        }

        @Override
        public void clearActionBar(Player viewer, String purpose) {
            calls.add("clearActionBar " + purpose);
        }

        @Override
        public void clearTitle(Player viewer, String purpose) {
            calls.add("clearTitle " + purpose);
        }

        @Override
        public boolean bossBar(Player viewer, String laneId, int priority, String title, double progress,
                               BarColor color, BarStyle style, long staleMillis) {
            calls.add("bossBar " + laneId + " " + priority + " " + title + " " + progress + " " + color + " "
                + style + " " + staleMillis);
            return true;
        }

        @Override
        public void hideBossBar(Player viewer, String laneId) {
            calls.add("hideBossBar " + laneId);
        }

        @Override
        public void title(Player viewer, String purpose, int priority, String title, String subtitle,
                          int fadeInTicks, int stayTicks, int fadeOutTicks) {
            calls.add("title " + purpose + " " + priority + " " + title + " " + subtitle + " " + fadeInTicks + " "
                + stayTicks + " " + fadeOutTicks);
        }

        @Override
        public void forget(UUID viewerId) {
        }
    }
}
