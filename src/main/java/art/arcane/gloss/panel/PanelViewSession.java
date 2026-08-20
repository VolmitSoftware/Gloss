package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.api.internal.ApiEvents;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.MenuSessionOptions;
import art.arcane.gloss.menu.MenuTransform;
import art.arcane.gloss.menu.action.MenuNavigationHistory;
import art.arcane.gloss.menu.action.MenuNavigator;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.volmlib.util.localization.MessageArgs;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class PanelViewSession implements MenuNavigator {
  private final Player viewer;
  private final Function<String, MenuDefinitionData> menuLookup;
  private final Consumer<PanelViewSession> closeRequester;
  private final MenuNavigationHistory navigation = new MenuNavigationHistory();

  private PanelDefinition definition;
  private PanelTransform effectiveTransform;
  private MenuSession session;
  private String currentMenuId;
  private boolean closed;

  public PanelViewSession(PanelViewOptions options) {
    this(
        Objects.requireNonNull(options, "options").definition(),
        options.effectiveTransform(),
        options.viewer(),
        menuId -> options.menus().definition(menuId).orElse(null),
        options.closeRequester()
    );
  }

  PanelViewSession(PanelDefinition definition, PanelTransform effectiveTransform, Player viewer,
                   Function<String, MenuDefinitionData> menuLookup,
                   Consumer<PanelViewSession> closeRequester) {
    this.definition = Objects.requireNonNull(definition, "definition");
    this.effectiveTransform = Objects.requireNonNull(effectiveTransform, "effectiveTransform");
    this.viewer = Objects.requireNonNull(viewer, "viewer");
    this.menuLookup = Objects.requireNonNull(menuLookup, "menuLookup");
    this.closeRequester = Objects.requireNonNull(closeRequester, "closeRequester");
  }

  public NavigationResult open() {
    return openMenu(definition.rootMenuId(), NavigationMode.REPLACE, false, true);
  }

  public void tick() {
    if (!closed && session != null) {
      session.tick();
    }
  }

  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (session != null) {
        session.close();
      }
    } finally {
      session = null;
      currentMenuId = null;
      navigation.clear();
    }
  }

  public NavigationResult update(PanelDefinition nextDefinition, PanelTransform nextEffectiveTransform) {
    if (closed) {
      return NavigationResult.DENIED;
    }
    PanelDefinition requiredDefinition = Objects.requireNonNull(nextDefinition, "nextDefinition");
    PanelTransform requiredTransform = Objects.requireNonNull(nextEffectiveTransform, "nextEffectiveTransform");
    boolean rootChanged = !definition.rootMenuId().equals(requiredDefinition.rootMenuId());
    boolean transformChanged = !effectiveTransform.equals(requiredTransform);
    boolean definitionChanged = !definition.equals(requiredDefinition);
    if (!rootChanged && !transformChanged && !definitionChanged) {
      return NavigationResult.APPLIED;
    }
    if (rootChanged) {
      PanelDefinition previousDefinition = definition;
      PanelTransform previousTransform = effectiveTransform;
      definition = requiredDefinition;
      effectiveTransform = requiredTransform;
      NavigationResult result = openMenu(requiredDefinition.rootMenuId(), NavigationMode.REPLACE, false, true);
      if (result != NavigationResult.APPLIED) {
        definition = previousDefinition;
        effectiveTransform = previousTransform;
        close();
      } else {
        navigation.clear();
      }
      return result;
    }
    definition = requiredDefinition;
    effectiveTransform = requiredTransform;
    if (transformChanged) {
      applyTransform();
    }
    return NavigationResult.APPLIED;
  }

  public PanelDefinition definition() {
    return definition;
  }

  public Player viewer() {
    return viewer;
  }

  public MenuSession session() {
    return session;
  }

  public String currentMenuId() {
    return currentMenuId;
  }

  public PanelTransform effectiveTransform() {
    return effectiveTransform;
  }

  public boolean closed() {
    return closed;
  }

  public boolean reloadCurrent() {
    return !closed && currentMenuId != null
        && openMenu(currentMenuId, NavigationMode.REPLACE, false, isAtRoot(currentMenuId)) == NavigationResult.APPLIED;
  }

  public boolean returnHome() {
    if (closed || definition.rootMenuId().equals(currentMenuId)) {
      return false;
    }
    navigation.clear();
    return openMenu(definition.rootMenuId(), NavigationMode.HOME, false, true) == NavigationResult.APPLIED;
  }

  public void dispatchClick(ClickableComponent<?> component, HoloClickTrigger trigger) {
    if (closed || session == null || component == null || !component.isOpen()) {
      return;
    }
    if (ApiEvents.fireClick(viewer, currentMenuId, component.getId(), null, trigger)) {
      component.onClick(trigger);
    }
  }

  @Override
  public NavigationResult navigate(NavigationRequest request) {
    if (closed) {
      return NavigationResult.DENIED;
    }
    NavigationRequest requiredRequest = Objects.requireNonNull(request, "request");
    if (requiredRequest.mode() == NavigationMode.CLOSE) {
      closeRequester.accept(this);
      return NavigationResult.APPLIED;
    }

    String target = navigation.resolveTarget(
        requiredRequest.mode(), requiredRequest.target(), definition.rootMenuId());
    if (target == null) {
      return NavigationResult.NO_HISTORY;
    }
    return openMenu(target, requiredRequest.mode(), true, target.equals(definition.rootMenuId()));
  }

  private NavigationResult openMenu(String menuId, NavigationMode mode, boolean notifyFailure,
                                    boolean boardRootAdmission) {
    MenuDefinitionData menu = menuLookup.apply(menuId);
    if (menu == null) {
      if (notifyFailure) {
        viewer.sendMessage(Gloss.instance.getLocalization().legacy(
            GlossMessages.MENU_UNAVAILABLE,
            MessageArgs.builder().untrusted("menu", menuId).build()
        ));
      }
      return NavigationResult.NOT_FOUND;
    }
    if (!boardRootAdmission && !viewer.hasPermission("gloss.open." + menu.getId())) {
      if (notifyFailure) {
        viewer.sendMessage(Gloss.instance.getLocalization().legacy(
            GlossMessages.MENU_PERMISSION_DENIED,
            MessageArgs.builder().untrusted("menu", menu.getId()).build()
        ));
      }
      return NavigationResult.DENIED;
    }
    if (!ApiEvents.fireOpen(viewer, menu.getId(), null)) {
      return NavigationResult.DENIED;
    }
    MenuTransform transform = PanelPlacement.menuTransform(effectiveTransform, menu);
    if (transform == null) {
      return NavigationResult.NOT_FOUND;
    }

    MenuSession previous = session;
    MenuSession replacement = new MenuSession(
        menu,
        viewer,
        MenuSessionOptions.positioned(transform, this, (float) effectiveTransform.scale())
    );
    try {
      replacement.open();
    } catch (RuntimeException | Error failure) {
      try {
        replacement.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      Gloss.logExceptionStack(false, failure, "Failed to open menu %s on panel %s for %s.",
          menuId, definition.id(), viewer.getName());
      return NavigationResult.DENIED;
    }

    navigation.commit(mode, currentMenuId);
    session = replacement;
    currentMenuId = menu.getId();
    if (previous != null) {
      previous.close();
    }
    return NavigationResult.APPLIED;
  }

  private boolean isAtRoot(String menuId) {
    return definition.rootMenuId().equals(menuId);
  }

  private void applyTransform() {
    if (session == null || currentMenuId == null) {
      return;
    }
    MenuDefinitionData menu = menuLookup.apply(currentMenuId);
    MenuTransform transform = menu == null ? null : PanelPlacement.menuTransform(effectiveTransform, menu);
    if (transform != null) {
      session.applyTransform(transform, (float) effectiveTransform.scale());
    }
  }
}
