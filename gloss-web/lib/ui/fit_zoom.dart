/// The zoom that fits a preview into its frame with no scrollbar: the
/// natural size when it fits, otherwise the largest zoom that fits both the
/// width and (when the frame has one) the height. Never above 1, never
/// under [minFitZoom]. Pixel art steps in whole device pixels per art pixel
/// so it stays crisp. Pure Dart.
library;

/// The smallest zoom a preview gets; past it the frame grows instead.
const double minFitZoom = 0.25;

double fitZoom({
  required double naturalWidth,
  required double naturalHeight,
  required double roomWidth,
  double? roomHeight,
  double dpr = 1,
  int pixel = 0,
}) {
  if (!(naturalWidth > 0) || !(naturalHeight > 0)) return 1;
  double scale = roomWidth / naturalWidth;
  if (roomHeight != null && roomHeight > 0) {
    final double tall = roomHeight / naturalHeight;
    if (tall < scale) scale = tall;
  }
  if (scale >= 1) return 1;
  double zoom;
  if (pixel > 0) {
    // One step is one device pixel per art pixel.
    final double steps = pixel * (dpr > 0 ? dpr : 1);
    zoom = (scale * steps + 1e-9).floorToDouble() / steps;
  } else {
    // Hundredths, so a frame a pixel narrower does not re-zoom.
    zoom = (scale * 100 + 1e-9).floorToDouble() / 100;
  }
  return zoom < minFitZoom ? minFitZoom : zoom;
}
