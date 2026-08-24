package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloCloseReason;
import art.arcane.gloss.api.internal.ApiMenuHandle;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.gloss.menu.action.MenuNavigationHistory;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.preview.ContainerPreview;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

class SessionHolder {
  private final Object sessionLock = new Object();
  private final Object previewLock = new Object();

  private final Player player;
  private final UUID playerId;
  private final PlayerSnapshotStore<String> openMenus;
  private final MenuNavigationHistory navigation = new MenuNavigationHistory();
  private transient volatile MenuSession session;
  private transient ContainerPreview preview;
  private String navigationRoot;

  /** Eye pose the last preview scan ran from, and what it acquired; see {@link #stableAim}. */
  private double aimX, aimY, aimZ;
  private float aimYaw, aimPitch;
  private Object aimTarget;

  SessionHolder(Player player, PlayerSnapshotStore<String> openMenus) {
    this.player = player;
    this.playerId = player.getUniqueId();
    this.openMenus = openMenus;
  }

  Player player() {
    return player;
  }

  UUID playerId() {
    return playerId;
  }

  /**
   * Whether this holder is carrying nothing. An idle holder is pruned by the tick sweep instead of
   * living on for the rest of the player's session.
   */
  boolean isIdle() {
    return session == null && preview == null;
  }

  boolean isDisposable() {
    return !player.isOnline() || isIdle();
  }

  /**
   * The target the last preview scan acquired, when a preview is open and the viewer's eye has not
   * moved since — a scan from an identical pose resolves the same ray. Returns null (and forgets
   * the record) otherwise, which is the signal to scan.
   */
  Object stableAim(Location eye) {
    synchronized (previewLock) {
      if (preview == null || aimTarget == null) {
        aimTarget = null;
        return null;
      }
      return eye.getX() == aimX && eye.getY() == aimY && eye.getZ() == aimZ
          && eye.getYaw() == aimYaw && eye.getPitch() == aimPitch
          ? aimTarget
          : null;
    }
  }

  void recordAim(Location eye, Object target) {
    synchronized (previewLock) {
      aimX = eye.getX();
      aimY = eye.getY();
      aimZ = eye.getZ();
      aimYaw = eye.getYaw();
      aimPitch = eye.getPitch();
      aimTarget = target;
    }
  }

  void openSession(MenuDefinitionData data, ApiMenuHandle handle) {
    Opened opened = openSessionLocked(data, handle, NavigationMode.PUSH);
    settle(opened);
  }

  NavigationResult navigateSession(MenuDefinitionData data, NavigationRequest request) {
    Opened opened;
    synchronized (sessionLock) {
      if (!matchesNavigationTarget(data, request.mode())) {
        return NavigationResult.NO_HISTORY;
      }
      opened = openSessionLocked(data, null, request.mode());
    }
    settle(opened);
    return opened.rejected() == null && opened.failure() == null
        ? NavigationResult.APPLIED
        : NavigationResult.DENIED;
  }

  private static void settle(Opened opened) {
    if (opened.replaced() != null) {
      opened.replaced().terminate(HoloCloseReason.REPLACED);
    }
    if (opened.rejected() != null) {
      opened.rejected().terminate(opened.failure() == null ? HoloCloseReason.QUIT : HoloCloseReason.OPEN_FAILED);
    }
    if (opened.failure() instanceof RuntimeException failure) {
      throw failure;
    }
    if (opened.failure() instanceof Error failure) {
      throw failure;
    }
  }

  void openPreview(ContainerPreview session) {
    synchronized (previewLock) {
      closePreview();
      preview = session;
      GlossTelemetry.incrementPreviewsOpen();
      preview.open();
    }
  }

  /** Package hook for the preview scan: the aim record only survives while a preview is live. */
  private void forgetAim() {
    aimTarget = null;
  }

  boolean tick() {
    if (!player.isOnline()) {
      closeSession(false, HoloCloseReason.QUIT);
      closePreview();
      return true;
    }

    if (isIdle()) {
      return true;
    }

    synchronized (sessionLock) {
      if (session != null) {
        session.tick();
      }
    }

    synchronized (previewLock) {
      if (preview != null) {
        try {
          if (!preview.tick()) {
            safelyClosePreview();
          }
        } catch (Exception ex) {
          Gloss.logExceptionStackThrottled(false, "container-preview-tick", ex,
              "Failed to tick preview for %s. Closing preview.", player.getName());
          safelyClosePreview();
        }
      }
    }
    return false;
  }

  boolean closeSession(boolean history, HoloCloseReason reason) {
    Detached detached = detachSession(history);
    if (detached == null) return false;
    if (detached.handle() != null) {
      detached.handle().terminate(reason);
    }
    return true;
  }

  boolean inspectSession(Function<@Nullable MenuSession, HoloCloseReason> action) {
    Closed closed = inspectSessionLocked(action);
    if (closed == null) return false;
    if (closed.handle() != null) {
      closed.handle().terminate(closed.reason());
    }
    return true;
  }

  boolean closeSessionOf(ApiMenuHandle handle, HoloCloseReason reason) {
    Detached detached = detachSessionOf(handle);
    if (detached == null) return false;
    handle.terminate(reason);
    return true;
  }

  void closePreview() {
    synchronized (previewLock) {
      if (preview == null) return;
      safelyClosePreview();
    }
  }

  void close(HoloCloseReason reason) {
    closeSession(false, reason);
    closePreview();
  }

  private void safelyClosePreview() {
    ContainerPreview current = preview;
    preview = null;
    forgetAim();
    if (current == null) {
      return;
    }

    GlossTelemetry.decrementPreviewsOpen();
    try {
      current.close();
    } catch (Exception ex) {
      Gloss.logExceptionStack(false, ex, "Failed to close preview cleanly for %s.", player.getName());
    }
  }

  void onSession(Consumer<@Nullable MenuSession> action) {
    synchronized (sessionLock) {
      action.accept(session);
    }
  }

  boolean hasSession() {
    return session != null;
  }

  boolean moveSession(Location anchor) {
    synchronized (sessionLock) {
      if (session == null) {
        return false;
      }
      session.move(anchor);
      return true;
    }
  }

  ClickSnapshot snapshotClick(Location eyeLocation) {
    MenuSession current = session;
    if (current == null) return null;

    ClickableComponent<?> nearest = null;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (MenuComponent<?> component : current.getComponents()) {
      if (!(component instanceof ClickableComponent<?> clickable)) {
        continue;
      }
      OptionalDouble distance = clickable.intersectionDistance(
          eyeLocation.toVector(),
          eyeLocation.getDirection()
      );
      if (distance.isPresent() && distance.getAsDouble() < nearestDistance) {
        nearest = clickable;
        nearestDistance = distance.getAsDouble();
      }
    }

    if (nearest == null) return null;
    return new ClickSnapshot(current.getId(), current.getApiHandle(), nearest, nearestDistance);
  }

  void onPreview(Consumer<@Nullable ContainerPreview> action) {
    synchronized (previewLock) {
      action.accept(preview);
    }
  }

  boolean hasPreview() {
    synchronized (previewLock) {
      return preview != null;
    }
  }

  String lastSessionId() {
    synchronized (sessionLock) {
      return navigation.last();
    }
  }

  String rootSessionId() {
    synchronized (sessionLock) {
      return navigationRoot;
    }
  }

  void refreshVisuals() {
    synchronized (sessionLock) {
      if (session != null) {
        List<MenuComponent<?>> openComponents = session.getComponents().stream()
            .filter(MenuComponent::isOpen)
            .toList();
        openComponents.forEach(MenuComponent::close);
        session.refreshScale();
        openComponents.forEach(MenuComponent::open);
      }
    }
    synchronized (previewLock) {
      if (preview != null) {
        preview.refreshVisuals();
      }
    }
  }

  private Opened openSessionLocked(MenuDefinitionData data, ApiMenuHandle handle, NavigationMode mode) {
    synchronized (sessionLock) {
      if (!player.isOnline()) return new Opened(null, handle, null);

      MenuSession previous = session;
      String previousMenuId = previous == null ? null : previous.getId();
      openMenus.publish(playerId, data.getId());
      MenuSession replacement = null;
      try {
        replacement = new MenuSession(data, player, MenuSessionOptions.personal(data, player, handle));
        replacement.open();
      } catch (RuntimeException | Error failure) {
        if (replacement != null) {
          try {
            replacement.close();
          } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
        session = previous;
        openMenus.publish(playerId, previousMenuId);
        return new Opened(null, handle, failure);
      }

      commitNavigation(mode, data.getId(), previousMenuId);
      session = replacement;
      if (handle != null) {
        handle.markOpen();
      }
      GlossTelemetry.incrementMenusOpen();
      ApiMenuHandle replacedHandle = previous == null ? null : previous.getApiHandle();
      if (previous != null) {
        try {
          previous.close();
        } catch (RuntimeException | Error failure) {
          Gloss.logExceptionStack(false, failure, "Failed to close replaced menu %s for %s.",
              previous.getId(), player.getName());
        } finally {
          GlossTelemetry.decrementMenusOpen();
        }
      }
      return new Opened(replacedHandle, null, null);
    }
  }

  private Detached detachSession(boolean history) {
    synchronized (sessionLock) {
      if (session == null) return null;
      if (history) {
        String closingMenuId = session.getId();
        if (navigationRoot == null) {
          navigationRoot = closingMenuId;
        }
        navigation.record(closingMenuId);
      } else {
        clearNavigation();
      }
      return detachCurrentSession();
    }
  }

  private void commitNavigation(NavigationMode mode, String targetId, String previousMenuId) {
    if (previousMenuId == null) {
      if (mode == NavigationMode.PUSH || (mode == NavigationMode.REPLACE && navigationRoot == null)) {
        navigationRoot = targetId;
      }
    } else if (navigationRoot == null) {
      navigationRoot = previousMenuId;
    }
    navigation.commit(mode, previousMenuId);
  }

  private Detached detachCurrentSession() {
    if (session == null) return null;
    ApiMenuHandle handle = session.getApiHandle();
    session.close();
    session = null;
    openMenus.publish(playerId, null);
    GlossTelemetry.decrementMenusOpen();
    return new Detached(handle);
  }

  private boolean matchesNavigationTarget(MenuDefinitionData data, NavigationMode mode) {
    if (data == null) {
      return false;
    }
    return Objects.equals(navigation.resolveTarget(mode, data.getId(), navigationRoot), data.getId());
  }

  private void clearNavigation() {
    navigation.clear();
    navigationRoot = null;
  }

  private Detached detachSessionOf(ApiMenuHandle handle) {
    synchronized (sessionLock) {
      if (session == null || session.getApiHandle() != handle) return null;
      return detachSession(false);
    }
  }

  private Closed inspectSessionLocked(Function<@Nullable MenuSession, HoloCloseReason> action) {
    synchronized (sessionLock) {
      HoloCloseReason reason = action.apply(session);
      if (reason == null) return null;
      Detached detached = detachSession(false);
      return detached == null ? null : new Closed(detached.handle(), reason);
    }
  }

  private record Detached(ApiMenuHandle handle) {
  }

  private record Closed(ApiMenuHandle handle, HoloCloseReason reason) {
  }

  private record Opened(ApiMenuHandle replaced, ApiMenuHandle rejected, Throwable failure) {
  }

  record ClickSnapshot(String menuId, ApiMenuHandle handle, ClickableComponent<?> component, double distance) {
  }
}
