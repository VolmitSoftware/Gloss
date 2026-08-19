package art.arcane.gloss.menu.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.menu.MenuSession;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class SessionActionContext implements ActionContext {
  private final MenuSession session;
  private final String componentId;
  private final MenuNavigator navigator;
  private final HoloClickTrigger trigger;

  public SessionActionContext(MenuSession session, String componentId, MenuNavigator navigator,
                              HoloClickTrigger trigger) {
    this.session = Objects.requireNonNull(session, "session");
    this.componentId = Objects.requireNonNull(componentId, "componentId");
    this.navigator = Objects.requireNonNull(navigator, "navigator");
    this.trigger = Objects.requireNonNull(trigger, "trigger");
  }

  @Override
  public Player player() {
    return session.getPlayer();
  }

  @Override
  public String menuId() {
    return session.getId();
  }

  @Override
  public String componentId() {
    return componentId;
  }

  @Override
  public HoloClickTrigger trigger() {
    return trigger;
  }

  @Override
  public NavigationResult navigate(NavigationRequest request) {
    return navigator.navigate(request);
  }
}
