package art.arcane.gloss.expr;

import java.util.List;
import java.util.regex.Pattern;

final class TextAnimationFunctions {
  private static final int NO_POSITION = -1;
  private static final int MAX_TEXT_CODE_POINTS = 256;
  private static final int MAX_STYLED_CODE_POINTS = 64;
  private static final int MAX_WINDOW_WIDTH = 64;
  private static final int MAX_TIMELINE_STEPS = 64;
  private static final int MAX_STYLES = 16;
  private static final double MAX_TIMELINE_SECONDS = 3600.0D;
  private static final double MAX_SAFE_WHOLE_NUMBER = 9_007_199_254_740_991.0D;
  private static final int[] SCRAMBLE_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$?".codePoints().toArray();
  private static final Pattern FORMATTING_TOKEN = Pattern.compile(
      "(?:&[0-9A-Fa-fK-Ok-oRr]|§[0-9A-Fa-fK-Ok-oRr]|\\[[0-9A-Fa-f]{6}]|%[^%\\s]+%)");
  private static final Pattern STYLE = Pattern.compile(
      "(?:&[0-9A-Fa-fRr]|\\[[0-9A-Fa-f]{6}])(?:&[0-9A-Fa-fK-Ok-oRr]|\\[[0-9A-Fa-f]{6}])?");

  private TextAnimationFunctions() {
  }

  static Object call(String name, List<Object> args) {
    return switch (name) {
      case "marquee" -> marquee(name, args);
      case "timeline" -> timeline(name, args);
      case "typewriter" -> typewriter(name, args);
      case "flash" -> flash(name, args);
      case "wipe" -> wipe(name, args);
      case "scanner" -> scanner(name, args);
      case "scramble" -> scramble(name, args);
      case "odometer" -> odometer(name, args);
      case "wave" -> wave(name, args);
      default -> null;
    };
  }

  static boolean isSupported(String name) {
    return switch (name) {
      case "marquee", "timeline", "typewriter", "flash", "wipe", "scanner", "scramble",
          "odometer", "wave" -> true;
      default -> false;
    };
  }

  private static String marquee(String name, List<Object> args) {
    requireCount(name, args, 3);
    int[] text = plainCodePoints(name, strArg(name, args, 0), MAX_TEXT_CODE_POINTS);
    int width = wholeArg(name, args, 1, 1, MAX_WINDOW_WIDTH);
    int start = floorModStep(numArg(name, args, 2), text.length + width);
    StringBuilder out = new StringBuilder(width);
    for (int index = 0; index < width; index++) {
      int source = (start + index) % (text.length + width);
      out.appendCodePoint(source < text.length ? text[source] : ' ');
    }
    return out.toString();
  }

  private static String timeline(String name, List<Object> args) {
    requireCount(name, args, 2);
    Object stepsArg = args.get(0);
    if (!(stepsArg instanceof List<?> steps)) {
      throw error(name + " argument 1 must be a list");
    }
    if (steps.isEmpty() || steps.size() > MAX_TIMELINE_STEPS) {
      throw error(name + " argument 1 must contain between 1 and " + MAX_TIMELINE_STEPS + " steps");
    }
    String[] texts = new String[steps.size()];
    double[] durations = new double[steps.size()];
    double total = 0.0D;
    for (int index = 0; index < steps.size(); index++) {
      Object stepArg = steps.get(index);
      if (!(stepArg instanceof List<?> step) || step.size() != 2 || !(step.get(0) instanceof String text)
          || !(step.get(1) instanceof Double duration) || !Double.isFinite(duration) || duration <= 0.0D) {
        throw error(name + " step " + (index + 1) + " must be [text, positiveSeconds]");
      }
      codePoints(name, text, MAX_TEXT_CODE_POINTS);
      texts[index] = text;
      durations[index] = duration;
      total += duration;
      if (total > MAX_TIMELINE_SECONDS) {
        throw error(name + " total duration must not exceed " + (int) MAX_TIMELINE_SECONDS + " seconds");
      }
    }
    double elapsed = numArg(name, args, 1);
    if (!Double.isFinite(elapsed)) {
      throw error(name + " argument 2 must be finite");
    }
    double position = elapsed % total;
    if (position < 0.0D) {
      position += total;
    }
    for (int index = 0; index < durations.length; index++) {
      if (position < durations[index]) {
        return texts[index];
      }
      position -= durations[index];
    }
    return texts[texts.length - 1];
  }

  private static String typewriter(String name, List<Object> args) {
    requireCount(name, args, 3);
    int[] text = plainCodePoints(name, strArg(name, args, 0), MAX_TEXT_CODE_POINTS);
    if (text.length == 0) {
      return "";
    }
    int hold = wholeArg(name, args, 2, 0, 1200);
    int phase = floorModStep(numArg(name, args, 1), (text.length * 2) + hold);
    int visible = phase <= text.length ? phase
        : phase < text.length + hold ? text.length : (text.length * 2) + hold - phase;
    return prefix(text, visible, false);
  }

  private static String flash(String name, List<Object> args) {
    requireCount(name, args, 3);
    String first = checkedText(name, strArg(name, args, 0), MAX_TEXT_CODE_POINTS);
    String second = checkedText(name, strArg(name, args, 1), MAX_TEXT_CODE_POINTS);
    return floorModStep(numArg(name, args, 2), 2) == 0 ? first : second;
  }

  private static String wipe(String name, List<Object> args) {
    requireCount(name, args, 2);
    int[] text = plainCodePoints(name, strArg(name, args, 0), MAX_TEXT_CODE_POINTS);
    if (text.length == 0) {
      return "";
    }
    int phase = floorModStep(numArg(name, args, 1), text.length * 2);
    int visible = phase <= text.length ? phase : (text.length * 2) - phase;
    return prefix(text, visible, true);
  }

  private static String scanner(String name, List<Object> args) {
    requireCount(name, args, 4);
    int[] text = plainCodePoints(name, strArg(name, args, 0), MAX_STYLED_CODE_POINTS);
    if (text.length == 0) {
      return "";
    }
    String base = strArg(name, args, 1);
    String highlight = strArg(name, args, 2);
    requireStyle(name, base);
    requireStyle(name, highlight);
    int active = floorModStep(numArg(name, args, 3), text.length);
    StringBuilder out = new StringBuilder(text.length + base.length() * 2 + highlight.length());
    out.append(active == 0 ? highlight : base);
    for (int index = 0; index < text.length; index++) {
      out.appendCodePoint(text[index]);
      if (index == active) {
        out.append(base);
      } else if (index + 1 == active) {
        out.append(highlight);
      }
    }
    return out.toString();
  }

  private static String scramble(String name, List<Object> args) {
    requireCount(name, args, 2);
    String source = strArg(name, args, 0);
    int[] text = plainCodePoints(name, source, MAX_TEXT_CODE_POINTS);
    if (text.length == 0) {
      return "";
    }
    int phase = floorModStep(numArg(name, args, 1), text.length + 2);
    int resolved = Math.min(phase, text.length);
    StringBuilder out = new StringBuilder(text.length);
    for (int index = 0; index < text.length; index++) {
      if (index < resolved || isAnimationWhitespace(text[index])) {
        out.appendCodePoint(text[index]);
      } else {
        int mixed = (phase * 31) + (index * 17) + text[index];
        out.appendCodePoint(SCRAMBLE_GLYPHS[Math.floorMod(mixed, SCRAMBLE_GLYPHS.length)]);
      }
    }
    return out.toString();
  }

  private static String odometer(String name, List<Object> args) {
    requireCount(name, args, 4);
    double from = numArg(name, args, 0);
    double to = numArg(name, args, 1);
    double progress = numArg(name, args, 2);
    if (!Double.isFinite(from) || !Double.isFinite(to) || !Double.isFinite(progress)) {
      throw error(name + " numeric arguments must be finite");
    }
    if (Math.abs(from) > MAX_SAFE_WHOLE_NUMBER || Math.abs(to) > MAX_SAFE_WHOLE_NUMBER) {
      throw error(name + " endpoints must stay within the safe whole-number range");
    }
    if (from != Math.rint(from) || to != Math.rint(to)) {
      throw error(name + " endpoints must be whole numbers");
    }
    int digits = wholeArg(name, args, 3, 1, 16);
    double interpolated = from + ((to - from) * Math.max(0.0D, Math.min(1.0D, progress)));
    if (!Double.isFinite(interpolated)) {
      throw error(name + " result must be finite");
    }
    long value = Math.round(interpolated);
    String raw = Long.toString(value);
    boolean negative = raw.charAt(0) == '-';
    String magnitude = negative ? raw.substring(1) : raw;
    String padded = "0".repeat(Math.max(0, digits - magnitude.length())) + magnitude;
    return negative ? "-" + padded : padded;
  }

  private static String wave(String name, List<Object> args) {
    requireCount(name, args, 3);
    int[] text = plainCodePoints(name, strArg(name, args, 0), MAX_STYLED_CODE_POINTS);
    if (text.length == 0) {
      return "";
    }
    Object stylesArg = args.get(1);
    if (!(stylesArg instanceof List<?> rawStyles) || rawStyles.isEmpty() || rawStyles.size() > MAX_STYLES) {
      throw error(name + " argument 2 must contain between 1 and " + MAX_STYLES + " styles");
    }
    String[] styles = new String[rawStyles.size()];
    for (int index = 0; index < rawStyles.size(); index++) {
      if (!(rawStyles.get(index) instanceof String style)) {
        throw error(name + " argument 2 entries must be strings");
      }
      requireStyle(name, style);
      styles[index] = style;
    }
    int start = floorModStep(numArg(name, args, 2), styles.length);
    StringBuilder out = new StringBuilder(text.length * 4);
    for (int index = 0; index < text.length; index++) {
      out.append(styles[(start + index) % styles.length]).appendCodePoint(text[index]);
    }
    return out.append(styles[0]).toString();
  }

  private static String checkedText(String name, String text, int maximum) {
    codePoints(name, text, maximum);
    return text;
  }

  private static int[] codePoints(String name, String text, int maximum) {
    requireSingleLine(name, text);
    int count = text.codePointCount(0, text.length());
    if (count > maximum) {
      throw error(name + " text must not exceed " + maximum + " characters");
    }
    return text.codePoints().toArray();
  }

  private static int[] plainCodePoints(String name, String text, int maximum) {
    if (FORMATTING_TOKEN.matcher(text).find()) {
      throw error(name + " text must be plain; put formatting outside the text argument");
    }
    int[] codePoints = codePoints(name, text, maximum);
    for (int codePoint : codePoints) {
      if (isComplexGraphemePart(codePoint)) {
        throw error(name + " text must use standalone characters, not combined emoji or marks");
      }
    }
    return codePoints;
  }

  private static boolean isComplexGraphemePart(int codePoint) {
    return codePoint == 0x200D
        || codePoint >= 0x0300 && codePoint <= 0x036F
        || codePoint >= 0x1AB0 && codePoint <= 0x1AFF
        || codePoint >= 0x1DC0 && codePoint <= 0x1DFF
        || codePoint >= 0x20D0 && codePoint <= 0x20FF
        || codePoint >= 0xFE00 && codePoint <= 0xFE0F
        || codePoint >= 0xFE20 && codePoint <= 0xFE2F
        || codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF
        || codePoint >= 0x1F3FB && codePoint <= 0x1F3FF
        || codePoint >= 0xE0020 && codePoint <= 0xE007F
        || codePoint >= 0xE0100 && codePoint <= 0xE01EF;
  }

  private static boolean isAnimationWhitespace(int codePoint) {
    return codePoint == 0x09 || codePoint == 0x0B || codePoint == 0x0C || codePoint == 0x20;
  }

  private static void requireStyle(String name, String value) {
    requireSingleLine(name, value);
    if (!STYLE.matcher(value).matches()) {
      throw error(name + " styles must start with a color or reset and contain at most two formatting codes");
    }
  }

  private static void requireSingleLine(String name, String value) {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf(0x85) >= 0
        || value.indexOf(0x2028) >= 0 || value.indexOf(0x2029) >= 0) {
      throw error(name + " text must stay on one line");
    }
  }

  private static String prefix(int[] text, int visible, boolean pad) {
    StringBuilder out = new StringBuilder(text.length);
    for (int index = 0; index < visible; index++) {
      out.appendCodePoint(text[index]);
    }
    if (pad) {
      out.append(" ".repeat(text.length - visible));
    }
    return out.toString();
  }

  private static int floorModStep(double value, int divisor) {
    if (!Double.isFinite(value)) {
      throw error("animation step must be finite");
    }
    return Math.floorMod((long) Math.floor(value), divisor);
  }

  private static int wholeArg(String name, List<Object> args, int index, int minimum, int maximum) {
    double value = numArg(name, args, index);
    if (value != Math.rint(value) || value < minimum || value > maximum) {
      throw error(name + " argument " + (index + 1) + " must be a whole number in [" + minimum + ", "
          + maximum + "]");
    }
    return (int) value;
  }

  private static void requireCount(String name, List<Object> args, int count) {
    if (args.size() != count) {
      throw error(name + " expects " + count + " argument(s), got " + args.size());
    }
  }

  private static double numArg(String name, List<Object> args, int index) {
    Object value = args.get(index);
    if (value instanceof Double number) {
      return number;
    }
    throw error(name + " argument " + (index + 1) + " must be a number");
  }

  private static String strArg(String name, List<Object> args, int index) {
    Object value = args.get(index);
    if (value instanceof String text) {
      return text;
    }
    throw error(name + " argument " + (index + 1) + " must be a string");
  }

  private static ExprException error(String message) {
    return new ExprException(message, NO_POSITION);
  }
}
