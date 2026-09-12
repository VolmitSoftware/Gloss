package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** The context a hologram click runs its action list in. */
public final class HologramActionContext implements ActionContext {
    /** The pages a hologram offers and the page each viewer is reading. */
    public interface Pages {
        List<HologramPage> pages();

        String current(UUID viewerId);

        void select(UUID viewerId, String pageId);
    }

    private final String hologramId;
    private final Pages pages;
    private final int lineIndex;
    private final Player player;
    private final HoloClickTrigger trigger;

    public HologramActionContext(String hologramId, Pages pages, int lineIndex, Player player,
                                 HoloClickTrigger trigger) {
        this.hologramId = hologramId;
        this.pages = pages;
        this.lineIndex = lineIndex;
        this.player = player;
        this.trigger = trigger;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public String menuId() {
        return "hologram:" + hologramId;
    }

    @Override
    public String componentId() {
        return lineIndex < 0 ? "hologram" : "line:" + lineIndex;
    }

    @Override
    public HoloClickTrigger trigger() {
        return trigger;
    }

    @Override
    public NavigationResult navigate(NavigationRequest request) {
        if (request.mode() != NavigationMode.PAGE) {
            return NavigationResult.NOT_FOUND;
        }
        UUID viewerId = player.getUniqueId();
        String next = HologramPage.resolve(pages.pages(), pages.current(viewerId), request.target());
        if (next == null) {
            return NavigationResult.NOT_FOUND;
        }
        pages.select(viewerId, next);
        return NavigationResult.APPLIED;
    }
}
