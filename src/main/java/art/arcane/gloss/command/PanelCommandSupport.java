package art.arcane.gloss.command;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelFollow;
import art.arcane.gloss.panel.PanelFollowRotation;
import art.arcane.gloss.panel.PanelFollowTransform;
import art.arcane.gloss.panel.PanelTransform;
import art.arcane.gloss.panel.PanelVisibility;
import art.arcane.gloss.panel.PanelVisibilityMode;
import org.bukkit.Location;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class PanelCommandSupport {
  private PanelCommandSupport() {
  }

  static double coordinate(String input, double base, String field) {
    if (input == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    String token = input.strip();
    if (token.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }

    double value;
    if (token.charAt(0) == '~') {
      String relative = token.substring(1);
      value = base + (relative.isEmpty() ? 0.0D : number(relative, field));
    } else {
      value = number(token, field);
    }
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(field + " must resolve to a finite number");
    }
    return value == 0.0D ? 0.0D : value;
  }

  static PanelTransform move(PanelTransform current, String x, String y, String z) {
    PanelTransform transform = Objects.requireNonNull(current, "current");
    return new PanelTransform(
        transform.worldKey(),
        transform.worldUuid(),
        coordinate(x, transform.x(), "x"),
        coordinate(y, transform.y(), "y"),
        coordinate(z, transform.z(), "z"),
        transform.yaw(),
        transform.pitch(),
        transform.roll(),
        transform.scale()
    );
  }

  static PanelTransform moveTo(PanelTransform current, String worldKey, UUID worldUuid,
                               double x, double y, double z) {
    PanelTransform transform = Objects.requireNonNull(current, "current");
    return new PanelTransform(
        worldKey,
        worldUuid,
        x,
        y,
        z,
        transform.yaw(),
        transform.pitch(),
        transform.roll(),
        transform.scale()
    );
  }

  static PanelTransform rotate(PanelTransform current, String yaw, String pitch, String roll) {
    PanelTransform transform = Objects.requireNonNull(current, "current");
    return new PanelTransform(
        transform.worldKey(),
        transform.worldUuid(),
        transform.x(),
        transform.y(),
        transform.z(),
        coordinate(yaw, transform.yaw(), "yaw"),
        coordinate(pitch, transform.pitch(), "pitch"),
        coordinate(roll, transform.roll(), "roll"),
        transform.scale()
    );
  }

  static PanelTransform scale(PanelTransform current, String scale) {
    PanelTransform transform = Objects.requireNonNull(current, "current");
    return new PanelTransform(
        transform.worldKey(),
        transform.worldUuid(),
        transform.x(),
        transform.y(),
        transform.z(),
        transform.yaw(),
        transform.pitch(),
        transform.roll(),
        coordinate(scale, transform.scale(), "scale")
    );
  }

  static PanelDefinition reencodeEffectiveTransform(PanelDefinition current,
                                                    PanelTransform effectiveTransform,
                                                    Location followTarget) {
    PanelDefinition board = Objects.requireNonNull(current, "current");
    PanelTransform effective = Objects.requireNonNull(effectiveTransform, "effectiveTransform");
    if (board.follow().targetPlayerUuid() == null) {
      return board.withTransform(effective);
    }
    PanelTransform relative = PanelFollowTransform.relativeTo(
        effective,
        Objects.requireNonNull(followTarget, "followTarget"),
        board.follow().rotation()
    );
    return board.withTransform(relative);
  }

  static PanelDefinition follow(PanelDefinition current, PanelTransform effectiveTransform,
                                Location target, UUID targetPlayerUuid,
                                PanelFollowRotation rotation) {
    PanelDefinition board = Objects.requireNonNull(current, "current");
    PanelTransform relative = PanelFollowTransform.relativeTo(
        Objects.requireNonNull(effectiveTransform, "effectiveTransform"),
        Objects.requireNonNull(target, "target"),
        Objects.requireNonNull(rotation, "rotation")
    );
    return board.withTransform(relative)
        .withFollow(PanelFollow.player(Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid"), rotation));
  }

  static PanelDefinition unfollow(PanelDefinition current, PanelTransform effectiveTransform) {
    return Objects.requireNonNull(current, "current")
        .withTransform(Objects.requireNonNull(effectiveTransform, "effectiveTransform"))
        .withFollow(PanelFollow.none());
  }

  static PanelTransform align(PanelTransform current, PanelTransform reference, String axes) {
    PanelTransform transform = Objects.requireNonNull(current, "current");
    PanelTransform source = Objects.requireNonNull(reference, "reference");
    if (!transform.worldUuid().equals(source.worldUuid()) || !transform.worldKey().equals(source.worldKey())) {
      throw new IllegalArgumentException("panels must be in the same world to align");
    }

    String selected = axes(axes);
    return new PanelTransform(
        transform.worldKey(),
        transform.worldUuid(),
        selected.indexOf('x') >= 0 ? source.x() : transform.x(),
        selected.indexOf('y') >= 0 ? source.y() : transform.y(),
        selected.indexOf('z') >= 0 ? source.z() : transform.z(),
        transform.yaw(),
        transform.pitch(),
        transform.roll(),
        transform.scale()
    );
  }

  static PanelVisibility visibility(PanelVisibility current, PanelVisibilityMode mode,
                                    String viewPermission, String interactPermission) {
    PanelVisibility existing = Objects.requireNonNull(current, "current");
    PanelVisibilityMode requestedMode = Objects.requireNonNull(mode, "mode");
    String view = permission(viewPermission);
    String interact = permission(interactPermission);
    return new PanelVisibility(requestedMode, view, interact,
        existing.viewRange(), existing.interactionRange());
  }

  static PanelVisibility permissions(PanelVisibility current, String viewPermission,
                                     String interactPermission) {
    PanelVisibility existing = Objects.requireNonNull(current, "current");
    String view = permission(viewPermission);
    String interact = permission(interactPermission);
    PanelVisibilityMode mode;
    if (view != null) {
      mode = PanelVisibilityMode.PERMISSION;
    } else if (existing.mode() == PanelVisibilityMode.HIDDEN && interact == null) {
      mode = PanelVisibilityMode.HIDDEN;
    } else {
      mode = PanelVisibilityMode.PUBLIC;
    }
    return new PanelVisibility(mode, view, interact,
        existing.viewRange(), existing.interactionRange());
  }

  static PanelDefinition copy(PanelDefinition source, String newId) {
    PanelDefinition board = Objects.requireNonNull(source, "source");
    return new PanelDefinition(
        PanelDefinition.CURRENT_SCHEMA_VERSION,
        newId,
        UUID.randomUUID(),
        PanelDefinition.INITIAL_REVISION,
        board.rootMenuId(),
        board.transform(),
        board.follow(),
        board.visibility()
    );
  }

  static double nonNegativeFinite(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0D) {
      throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
    return value;
  }

  static Throwable rootCause(Throwable failure) {
    Throwable cause = failure;
    while (cause != null && cause.getCause() != null
        && (cause instanceof java.util.concurrent.CompletionException
        || cause instanceof java.util.concurrent.ExecutionException)) {
      cause = cause.getCause();
    }
    return cause == null ? failure : cause;
  }

  private static double number(String input, String field) {
    try {
      return Double.parseDouble(input);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(field + " must be a number or ~relative value");
    }
  }

  static String axes(String input) {
    if (input == null) {
      throw new IllegalArgumentException("axes must not be null");
    }
    String axes = input.strip().toLowerCase(Locale.ROOT);
    return switch (axes) {
      case "x", "y", "z", "xy", "xz", "yz", "xyz" -> axes;
      default -> throw new IllegalArgumentException("axes must be x, y, z, xy, xz, yz, or xyz");
    };
  }

  private static String permission(String input) {
    if (input == null) {
      return null;
    }
    String value = input.strip();
    return value.isEmpty() || value.equals("-") ? null : value;
  }
}
