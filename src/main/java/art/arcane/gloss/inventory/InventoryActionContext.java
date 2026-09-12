package art.arcane.gloss.inventory;

import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.MenuNavigator;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * The context a chest slot's actions run in. Its scope answers {@code args.*} and {@code session.*}
 * from the open window before falling back to the viewer, so a shop slot can read the arguments the
 * window was opened with.
 */
public final class InventoryActionContext implements ActionContext {
    private final Player player;
    private final String inventoryId;
    private final int slot;
    private final HoloClickTrigger trigger;
    private final Map<String, Object> args;
    private final MenuNavigator navigator;

    public InventoryActionContext(Player player, String inventoryId, int slot, HoloClickTrigger trigger,
                                  Map<String, Object> args, MenuNavigator navigator) {
        this.player = player;
        this.inventoryId = inventoryId;
        this.slot = slot;
        this.trigger = trigger;
        this.args = args == null ? Map.of() : Map.copyOf(args);
        this.navigator = navigator;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public String menuId() {
        return "inventory:" + inventoryId;
    }

    @Override
    public String componentId() {
        return "slot:" + slot;
    }

    @Override
    public HoloClickTrigger trigger() {
        return trigger;
    }

    @Override
    public NavigationResult navigate(NavigationRequest request) {
        return navigator == null ? NavigationResult.DENIED : navigator.navigate(request);
    }

    @Override
    public ExprScope conditionScope() {
        return new WindowScope(player, args);
    }

    @Override
    public SessionVariables sessionVariables() {
        InventoryMenuService service = InventoryMenuService.active();
        return service == null || player == null ? null : service.sessionVariables(player.getUniqueId());
    }

    @Override
    public void closeSurface(Player viewer) {
        InventoryMenuService service = InventoryMenuService.active();
        if (service != null) {
            service.close(viewer == null ? player : viewer);
        }
    }

    /**
     * The window's own arguments in front of the viewer's condition scope. The viewer scope is built
     * on first use so a gate that only reads {@code args.*} never pays for one.
     */
    private static final class WindowScope implements ExprScope {
        private static final String ARGS = "args.";

        private final Player viewer;
        private final Map<String, Object> args;
        private ExprScope delegate;
        private boolean delegateResolved;

        private WindowScope(Player viewer, Map<String, Object> args) {
            this.viewer = viewer;
            this.args = args;
        }

        @Override
        public Object variable(String dottedName) {
            if (dottedName == null) {
                return null;
            }
            if (dottedName.startsWith(ARGS)) {
                return args.get(dottedName.substring(ARGS.length()));
            }
            ExprScope scope = delegate();
            return scope == null ? null : scope.variable(dottedName);
        }

        @Override
        public Object call(String name, List<Object> callArgs) {
            ExprScope scope = delegate();
            return scope == null ? null : scope.call(name, callArgs);
        }

        private ExprScope delegate() {
            if (!delegateResolved) {
                delegateResolved = true;
                Gloss plugin = Gloss.instance;
                delegate = plugin == null || viewer == null || plugin.text() == null
                    ? null
                    : GlossConditionScope.viewer(plugin, viewer);
            }
            return delegate;
        }
    }
}
