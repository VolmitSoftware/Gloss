package art.arcane.gloss.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CmiText {
  private static final Pattern COLOR_TOKEN = Pattern.compile("\\{#([A-Za-z0-9_]+)(>|<>|<)?}");
  private static final Pattern AMPERSAND_HEX = Pattern.compile("&#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})(?![0-9A-Fa-f])");
  private static final Pattern BARE_HEX = Pattern.compile("(?<![{:\\[&0-9A-Za-z])#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})(?![0-9A-Fa-f])");
  private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9A-Fa-fK-Ok-oRrXx])");
  private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3}");
  private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
      Map.entry("black", "000000"), Map.entry("darkblue", "0000aa"),
      Map.entry("darkgreen", "00aa00"), Map.entry("darkaqua", "00aaaa"),
      Map.entry("darkred", "aa0000"), Map.entry("darkpurple", "aa00aa"),
      Map.entry("gold", "ffaa00"), Map.entry("gray", "aaaaaa"),
      Map.entry("darkgray", "555555"), Map.entry("blue", "5555ff"),
      Map.entry("green", "55ff55"), Map.entry("aqua", "55ffff"),
      Map.entry("red", "ff5555"), Map.entry("lightpurple", "ff55ff"),
      Map.entry("yellow", "ffff55"), Map.entry("white", "ffffff"));
  private static final Map<String, String> GLOBAL_COLORS = Map.of(
      "{gcp}", "ffaa00", "{gcs}", "ffff55", "{gcw}", "ff7357", "{gcn}", "aaaaaa",
      "{gcu}", "59c959", "{gcd}", "e89280", "{gce}", "ff5555");
  private static final Map<String, String> VARIABLES = Map.of(
      "[playerName]", "{{ player.name }}",
      "[playerDisplayName]", "{{ player.displayName }}",
      "%server_online%", "{{ server.online }}",
      "%server_max_players%", "{{ server.maxPlayers }}",
      "%player_ping%", "{{ player.ping }}");

  private CmiText() {
  }

  static String translate(String source, List<String> warnings) {
    if (source == null || source.isEmpty()) {
      return "";
    }
    String text = source;
    for (Map.Entry<String, String> variable : VARIABLES.entrySet()) {
      text = text.replace(variable.getKey(), variable.getValue());
    }
    for (Map.Entry<String, String> color : GLOBAL_COLORS.entrySet()) {
      text = text.replace(color.getKey(), "[" + color.getValue() + "]");
    }
    text = colorTokens(text, warnings);
    text = AMPERSAND_HEX.matcher(text).replaceAll(match -> "[" + expand(match.group(1)) + "]");
    return BARE_HEX.matcher(text).replaceAll(match -> "[" + expand(match.group(1)) + "]");
  }

  static String color(String token, List<String> warnings) {
    if (token == null || token.isBlank()) {
      return null;
    }
    String value = token.strip();
    if (value.startsWith("{#") && value.endsWith("}")) {
      value = value.substring(2, value.length() - 1);
    } else if (value.startsWith("#")) {
      value = value.substring(1);
    }
    return resolve(value, warnings);
  }

  private static String colorTokens(String text, List<String> warnings) {
    Matcher matcher = COLOR_TOKEN.matcher(text);
    StringBuilder output = new StringBuilder(text.length() + 16);
    int cursor = 0;
    String gradientStart = null;
    int gradientFrom = -1;
    while (matcher.find()) {
      String marker = matcher.group(2);
      String color = resolve(matcher.group(1), warnings);
      if (gradientStart != null && marker != null && marker.startsWith("<")) {
        String span = text.substring(gradientFrom, matcher.start());
        output.append(gradient(span, gradientStart, color == null ? gradientStart : color));
        cursor = matcher.end();
        gradientStart = marker.equals("<>") ? color : null;
        gradientFrom = cursor;
        continue;
      }
      if (gradientStart != null) {
        continue;
      }
      output.append(text, cursor, matcher.start());
      cursor = matcher.end();
      if (">".equals(marker) && color != null) {
        gradientStart = color;
        gradientFrom = cursor;
      } else if (color != null) {
        output.append('[').append(color).append(']');
      }
    }
    if (gradientStart != null) {
      output.append('[').append(gradientStart).append(']').append(text, gradientFrom, text.length());
      return output.toString();
    }
    return output.append(text, cursor, text.length()).toString();
  }

  private static String gradient(String span, String from, String to) {
    StringBuilder formats = new StringBuilder();
    Matcher codes = LEGACY_CODE.matcher(span);
    while (codes.find()) {
      char code = Character.toLowerCase(codes.group(1).charAt(0));
      if (code >= 'k' && code <= 'o' && formats.indexOf("&" + code) < 0) {
        formats.append('&').append(code);
      }
    }
    String plain = LEGACY_CODE.matcher(AMPERSAND_HEX.matcher(COLOR_TOKEN.matcher(span).replaceAll(""))
        .replaceAll("")).replaceAll("");
    List<String> characters = new ArrayList<>();
    plain.codePoints().forEach(codePoint -> characters.add(new String(Character.toChars(codePoint))));
    if (characters.isEmpty()) {
      return "";
    }
    int[] start = rgb(from);
    int[] end = rgb(to);
    double divisor = Math.max(characters.size() - 1, 1);
    StringBuilder output = new StringBuilder(characters.size() * 10);
    for (int index = 0; index < characters.size(); index++) {
      double amount = index / divisor;
      output.append('[').append(String.format(Locale.ROOT, "%02x%02x%02x",
          mix(start[0], end[0], amount), mix(start[1], end[1], amount), mix(start[2], end[2], amount)))
          .append(']').append(formats).append(characters.get(index));
    }
    return output.toString();
  }

  private static String resolve(String value, List<String> warnings) {
    if (HEX.matcher(value).matches()) {
      return expand(value);
    }
    String name = value.toLowerCase(Locale.ROOT).replace("_", "");
    String named = NAMED_COLORS.get(name);
    if (named == null) {
      warnings.add("CMI color name '" + value + "' has no Gloss equivalent and was removed");
    }
    return named;
  }

  private static String expand(String hex) {
    String lower = hex.toLowerCase(Locale.ROOT);
    if (lower.length() == 6) {
      return lower;
    }
    return "" + lower.charAt(0) + lower.charAt(0) + lower.charAt(1) + lower.charAt(1)
        + lower.charAt(2) + lower.charAt(2);
  }

  private static int[] rgb(String hex) {
    int value = Integer.parseInt(hex, 16);
    return new int[] {(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF};
  }

  private static int mix(int from, int to, double amount) {
    return (int) Math.round(from + (to - from) * amount);
  }
}
