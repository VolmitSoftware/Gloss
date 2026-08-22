package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;
import art.arcane.gloss.exceptions.MenuIconException;

import java.util.List;

public record AnimatedImageData(List<String> source,
                                int speed,
                                IconDisplayStyle style) implements MenuIconData {
  public AnimatedImageData {
    if (speed < 2 || speed > 1200) {
      throw new IllegalArgumentException("Animated image speed must be between 2 and 1200 ticks");
    }
  }

  public MenuIconType getType() {
    return MenuIconType.ANIMATED_TEXT_IMAGE;
  }

  public List<String> requireSource() throws MenuIconException {
    if (source == null || source.isEmpty())
      throw new MenuIconException("Animated icon has no source frames");
    for (String frame : source) {
      if (frame == null || frame.isBlank())
        throw new MenuIconException("Animated icon has a frame without a path");
    }
    return source;
  }
}
