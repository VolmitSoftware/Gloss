package art.arcane.gloss.preview.doc;

import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.preview.PreviewElement;
import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Measures a document's content and wraps it in the card chrome: outer frame, panel, optional
 * tray behind the cell grid, title bar, and title label.
 *
 * <p>The arithmetic, the constants, the integer division, and the emitted element order are all
 * frozen: the golden snapshots in {@code src/test/resources/golden} are the regression record for
 * the whole JSON preview engine and they pin every one of these numbers. Nothing here may be
 * "cleaned up": {@code (panelTop + titleBarBottom) / 2} truncates towards zero and the snapshots
 * depend on it.
 *
 * <p>The accent colour arrives as an int and the minimum panel half-width as a parameter, which the
 * parser defaults to {@code MIN_PANEL_HALF_WIDTH} when a document omits {@code card.minHalfWidth}.
 * The title arrives as a {@link Supplier} because {@link PreviewElement.Label} holds one, even
 * though a card title is evaluated once per build rather than per frame.
 */
public final class CardFramer {

  private static final int WELL = 18;
  private static final int LINE = 12;

  private static final int Z_FRAME = 0;
  private static final int Z_PANEL = 1;
  private static final int Z_TRAY = 2;
  private static final int Z_TITLE_BAR = 3;
  private static final int Z_LABEL = 6;

  private CardFramer() {
  }

  public record Settings(Supplier<Component> title, int accent, int minHalfWidth,
                         PreviewCardStyle style, IconDisplayStyle textStyle) {
  }

  public static List<PreviewElement> frame(List<PreviewElement> content, Settings settings) {
    PreviewCardStyle style = settings.style();
    Supplier<Component> title = settings.title();
    boolean hasGrid = false;
    int gridLeft = 0;
    int gridRight = 0;
    int gridBottom = 0;
    int gridTop = 0;
    int contentTop = Integer.MIN_VALUE;
    int contentBottom = Integer.MAX_VALUE;
    for (PreviewElement element : content) {
      int halfHeight = element instanceof PreviewElement.Label ? LINE / 2 : WELL / 2;
      contentTop = Math.max(contentTop, element.y() + halfHeight);
      contentBottom = Math.min(contentBottom, element.y() - halfHeight);
      boolean isCell = element instanceof PreviewElement.Slot || element instanceof PreviewElement.Cell;
      if (isCell) {
        int left = element.x() - WELL / 2;
        int right = element.x() + WELL / 2;
        int bottom = element.y() - WELL / 2;
        int top = element.y() + WELL / 2;
        if (!hasGrid) {
          gridLeft = left;
          gridRight = right;
          gridBottom = bottom;
          gridTop = top;
          hasGrid = true;
        } else {
          gridLeft = Math.min(gridLeft, left);
          gridRight = Math.max(gridRight, right);
          gridBottom = Math.min(gridBottom, bottom);
          gridTop = Math.max(gridTop, top);
        }
      }
    }
    if (contentTop == Integer.MIN_VALUE) {
      contentTop = WELL / 2;
      contentBottom = -WELL / 2;
    }

    int panelHalfWidth = Math.max(settings.minHalfWidth(), (hasGrid ? (gridRight - gridLeft) / 2 : WELL / 2) + style.padding());
    int titleBarBottom = contentTop + style.titleGap();
    int panelTop = titleBarBottom + style.titleHeight();
    int panelBottom = contentBottom - style.padding();
    int panelCenterY = (panelTop + panelBottom) / 2;
    int panelWidth = panelHalfWidth * 2;
    int panelHeight = panelTop - panelBottom;

    int accent = settings.accent();
    int frameColor = style.borderArgb() == null ? 0xCC000000 | (accent & 0xFFFFFF) : style.borderArgb().argb();
    int titleBarColor = style.titleArgb() == null ? 0xE6000000 | (accent & 0xFFFFFF) : style.titleArgb().argb();

    List<PreviewElement> styled = new ArrayList<>();
    styled.add(new PreviewElement.Panel(0, panelCenterY, Z_FRAME, panelWidth + style.borderWidth() * 2, panelHeight + style.borderWidth() * 2, frameColor, settings.textStyle()));
    styled.add(new PreviewElement.Panel(0, panelCenterY, Z_PANEL, panelWidth, panelHeight, style.backgroundArgb().argb(), settings.textStyle()));
    if (hasGrid) {
      int trayWidth = (gridRight - gridLeft) + style.trayPadding() * 2;
      int trayHeight = (gridTop - gridBottom) + style.trayPadding() * 2;
      int trayCenterX = (gridRight + gridLeft) / 2;
      int trayCenterY = (gridTop + gridBottom) / 2;
      styled.add(new PreviewElement.Panel(trayCenterX, trayCenterY, Z_TRAY, trayWidth, trayHeight, style.trayArgb().argb(), settings.textStyle()));
    }
    int titleBarCenterY = (panelTop + titleBarBottom) / 2;
    styled.add(new PreviewElement.Panel(0, titleBarCenterY, Z_TITLE_BAR, panelWidth, style.titleHeight(), titleBarColor, settings.textStyle()));
    styled.add(new PreviewElement.Label(0, titleBarCenterY, Z_LABEL, title,
        () -> new ParticleText.Rendered(TextUtils.content(title.get()), List.of()), 0, settings.textStyle(), null));
    styled.addAll(content);
    return styled;
  }
}
