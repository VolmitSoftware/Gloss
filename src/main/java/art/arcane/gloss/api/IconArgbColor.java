package art.arcane.gloss.api;

import java.util.Locale;

public record IconArgbColor(int argb) {
  public static final IconArgbColor TRANSPARENT = new IconArgbColor(0);

  public static IconArgbColor parse(String value) {
    if (value == null || !value.matches("#[0-9A-Fa-f]{8}")) {
      throw new IllegalArgumentException("ARGB colors must use #AARRGGBB");
    }
    long bits = Long.parseUnsignedLong(value.substring(1), 16);
    return new IconArgbColor((int) bits);
  }

  public String hex() {
    return String.format(Locale.ROOT, "#%08X", Integer.toUnsignedLong(argb));
  }
}
