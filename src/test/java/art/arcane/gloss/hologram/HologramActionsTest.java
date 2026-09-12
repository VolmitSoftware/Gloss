package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HologramActionsTest {
    private static final String PAGED = """
        {
          "schemaVersion": 3, "revision": 1,
          "anchor": {"world": "world", "position": [0, 64, 0]},
          "pages": [
            { "id": "1", "lines": ["Page one"] },
            { "id": "2", "lines": ["Page two"] }
          ],
          "actions": [ { "type": "navigate", "mode": "page", "target": "next", "trigger": "left_click" } ]
        }
        """;

    private static final class Pages implements HologramActionContext.Pages {
        private final List<HologramPage> pages;
        private final Map<UUID, String> selected = new HashMap<>();

        private Pages(List<HologramPage> pages) {
            this.pages = pages;
        }

        @Override
        public List<HologramPage> pages() {
            return pages;
        }

        @Override
        public String current(UUID viewerId) {
            return selected.get(viewerId);
        }

        @Override
        public void select(UUID viewerId, String pageId) {
            selected.put(viewerId, pageId);
        }
    }

    @Test
    void theContextNamesTheHologramAndTheClickedLine() {
        Player viewer = viewer();
        HologramActionContext context = new HologramActionContext("shop",
            new Pages(List.of()), 2, viewer, HoloClickTrigger.RIGHT_CLICK);

        assertEquals("hologram:shop", context.menuId());
        assertEquals("line:2", context.componentId());
        assertEquals(HoloClickTrigger.RIGHT_CLICK, context.trigger());
        assertEquals(viewer, context.player());
    }

    @Test
    void wholeHologramClicksCarryNoLineIndex() {
        HologramActionContext context = new HologramActionContext("shop",
            new Pages(List.of()), -1, viewer(), HoloClickTrigger.LEFT_CLICK);

        assertEquals("hologram", context.componentId());
    }

    @Test
    void pageNavigationMovesTheViewerAndUnknownTargetsAreRefused() {
        HologramDoc doc = HologramDoc.parse("paged.json", PAGED);
        Pages pages = new Pages(doc.pages());
        Player viewer = viewer();
        HologramActionContext context = new HologramActionContext("shop", pages, 0, viewer,
            HoloClickTrigger.LEFT_CLICK);

        assertEquals(NavigationResult.APPLIED, context.navigate(new NavigationRequest(NavigationMode.PAGE, "next")));
        assertEquals("2", pages.current(viewer.getUniqueId()));
        assertEquals(NavigationResult.APPLIED, context.navigate(new NavigationRequest(NavigationMode.PAGE, "prev")));
        assertEquals("1", pages.current(viewer.getUniqueId()));
        assertEquals(NavigationResult.NOT_FOUND, context.navigate(new NavigationRequest(NavigationMode.PAGE, "9")));
        assertEquals("1", pages.current(viewer.getUniqueId()));
        assertEquals(NavigationResult.NOT_FOUND, context.navigate(new NavigationRequest(NavigationMode.BACK, null)));
    }

    @Test
    void documentActionsRunThroughTheContextOnTheirTrigger() {
        HologramDoc doc = HologramDoc.parse("paged.json", PAGED);
        Pages pages = new Pages(doc.pages());
        Player viewer = viewer();
        List<MenuAction<?>> actions = MenuAction.resolve(List.<MenuActionData>copyOf(doc.actions()),
            "hologram:shop", "hologram");

        MenuAction.execute(actions, new HologramActionContext("shop", pages, -1, viewer,
            HoloClickTrigger.RIGHT_CLICK));
        assertNull(pages.current(viewer.getUniqueId()));

        MenuAction.execute(actions, new HologramActionContext("shop", pages, -1, viewer,
            HoloClickTrigger.LEFT_CLICK));
        assertEquals("2", pages.current(viewer.getUniqueId()));
    }

    private static Player viewer() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(HologramActionsTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Viewer";
                case "isOnline" -> true;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Viewer";
                default -> null;
            });
    }
}
