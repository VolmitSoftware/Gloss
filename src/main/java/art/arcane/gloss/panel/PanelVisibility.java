package art.arcane.gloss.panel;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PanelVisibility(PanelVisibilityMode mode, String viewPermission, String interactPermission,
                              double viewRange, double interactionRange) {
  public static final double DEFAULT_VIEW_RANGE = 64.0D;
  public static final double DEFAULT_INTERACTION_RANGE = 8.0D;
  public static final double MAX_VIEW_RANGE = 256.0D;
  public static final double MAX_INTERACTION_RANGE = 32.0D;

  private static final Pattern PERMISSION = Pattern.compile("[a-z0-9][a-z0-9._-]*");

  public PanelVisibility {
    mode = Objects.requireNonNull(mode, "mode");
    viewPermission = normalizePermission(viewPermission, "viewPermission");
    interactPermission = normalizePermission(interactPermission, "interactPermission");
    viewRange = requirePositiveFinite(viewRange, "viewRange");
    interactionRange = requirePositiveFinite(interactionRange, "interactionRange");

    if (mode == PanelVisibilityMode.PERMISSION && viewPermission == null) {
      throw new IllegalArgumentException("permission visibility requires viewPermission");
    }
    if (mode != PanelVisibilityMode.PERMISSION && viewPermission != null) {
      throw new IllegalArgumentException("viewPermission is only valid for permission visibility");
    }
    if (mode == PanelVisibilityMode.HIDDEN && interactPermission != null) {
      throw new IllegalArgumentException("hidden panels cannot declare interactPermission");
    }
    if (interactionRange > viewRange) {
      throw new IllegalArgumentException("interactionRange must not exceed viewRange");
    }
    if (viewRange > MAX_VIEW_RANGE) {
      throw new IllegalArgumentException("viewRange must not exceed " + MAX_VIEW_RANGE);
    }
    if (interactionRange > MAX_INTERACTION_RANGE) {
      throw new IllegalArgumentException("interactionRange must not exceed " + MAX_INTERACTION_RANGE);
    }
  }

  public static PanelVisibility publicAccess() {
    return new PanelVisibility(PanelVisibilityMode.PUBLIC, null, null,
        DEFAULT_VIEW_RANGE, DEFAULT_INTERACTION_RANGE);
  }

  public static PanelVisibility publicView(String interactPermission) {
    return new PanelVisibility(PanelVisibilityMode.PUBLIC, null, interactPermission,
        DEFAULT_VIEW_RANGE, DEFAULT_INTERACTION_RANGE);
  }

  public static PanelVisibility permission(String viewPermission, String interactPermission) {
    return new PanelVisibility(PanelVisibilityMode.PERMISSION, viewPermission, interactPermission,
        DEFAULT_VIEW_RANGE, DEFAULT_INTERACTION_RANGE);
  }

  public static PanelVisibility hidden() {
    return new PanelVisibility(PanelVisibilityMode.HIDDEN, null, null,
        DEFAULT_VIEW_RANGE, DEFAULT_INTERACTION_RANGE);
  }

  public PanelVisibility withRanges(double viewRange, double interactionRange) {
    return new PanelVisibility(mode, viewPermission, interactPermission, viewRange, interactionRange);
  }

  private static String normalizePermission(String value, String field) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty() || !PERMISSION.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " must be a Bukkit permission node");
    }
    return normalized;
  }

  private static double requirePositiveFinite(double value, String field) {
    if (!Double.isFinite(value) || value <= 0.0D) {
      throw new IllegalArgumentException(field + " must be finite and greater than zero");
    }
    return value;
  }
}
