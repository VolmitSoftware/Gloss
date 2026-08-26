package art.arcane.gloss.util.common;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TextUtils {
  private static final Map<Character, String> LEGACY_TAGS = legacyTags();
  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
      .character(LegacyComponentSerializer.SECTION_CHAR)
      .hexColors()
      .useUnusualXRepeatedCharacterHexFormat()
      .build();

  public static Component parse(String text) {
    return MiniMessage.miniMessage().deserialize(translateLegacy(text));
  }

  /**
   * Section codes only. Used for text that carries player typed content, where a MiniMessage pass
   * would turn whatever the player wrote between angle brackets into markup.
   */
  public static Component parseLegacy(String text) {
    return LEGACY.deserialize(text == null ? "" : text);
  }

  public static Component textColor(String text, String hexColor) {
    return Component.text(text).color(TextColor.fromHexString(hexColor));
  }

  public static Component textColor(String text, int hexColor) {
    return Component.text(text).color(TextColor.color(hexColor));
  }

  public static String content(Component component) {
    StringBuilder builder = new StringBuilder();
    if (component instanceof TextComponent text) {
      builder.append(text.content());
    }

    for (Component child : component.children()) {
      builder.append(content(child));
    }
    return builder.toString();
  }

  public static String joinLegacyLines(List<String> lines) {
    if (lines == null || lines.isEmpty()) {
      return "";
    }
    return String.join(ChatColor.RESET + "\n", lines);
  }

  public static String scopeLegacyLines(String text) {
    if (text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    StringBuilder output = null;
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      if (value != '\n' && value != '\r' && value != '\u2028' && value != '\u2029') {
        if (output != null) {
          output.append(value);
        }
        continue;
      }
      if (output == null) {
        output = new StringBuilder(text.length() + 8);
        output.append(text, 0, index);
      }
      output.append(value);
      if (value == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
        output.append('\n');
        index++;
      }
      output.append(ChatColor.RESET);
    }
    return output == null ? text : output.toString();
  }

  static String translateLegacy(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    if (text.indexOf('&') < 0 && text.indexOf(ChatColor.COLOR_CHAR) < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      String hex = legacyHex(text, i);
      if (hex != null) {
        out.append(hex);
        i += 13;
        continue;
      }
      String tag = (c == '&' || c == ChatColor.COLOR_CHAR) && i + 1 < text.length()
          ? LEGACY_TAGS.get(Character.toLowerCase(text.charAt(i + 1)))
          : null;
      if (tag == null) {
        out.append(c);
        continue;
      }
      out.append(tag);
      i++;
    }
    return out.toString();
  }

  private static String legacyHex(String text, int offset) {
    if (offset + 13 >= text.length()) {
      return null;
    }
    char marker = text.charAt(offset);
    if ((marker != '&' && marker != ChatColor.COLOR_CHAR)
        || Character.toLowerCase(text.charAt(offset + 1)) != 'x') {
      return null;
    }
    StringBuilder hex = new StringBuilder(15).append("<reset><#");
    for (int index = offset + 2; index < offset + 14; index += 2) {
      char lead = text.charAt(index);
      char digit = text.charAt(index + 1);
      if ((lead != '&' && lead != ChatColor.COLOR_CHAR) || Character.digit(digit, 16) < 0) {
        return null;
      }
      hex.append(digit);
    }
    return hex.append('>').toString();
  }

  private static Map<Character, String> legacyTags() {
    Map<Character, String> tags = new HashMap<>();
    tags.put('0', "<reset><black>");
    tags.put('1', "<reset><dark_blue>");
    tags.put('2', "<reset><dark_green>");
    tags.put('3', "<reset><dark_aqua>");
    tags.put('4', "<reset><dark_red>");
    tags.put('5', "<reset><dark_purple>");
    tags.put('6', "<reset><gold>");
    tags.put('7', "<reset><gray>");
    tags.put('8', "<reset><dark_gray>");
    tags.put('9', "<reset><blue>");
    tags.put('a', "<reset><green>");
    tags.put('b', "<reset><aqua>");
    tags.put('c', "<reset><red>");
    tags.put('d', "<reset><light_purple>");
    tags.put('e', "<reset><yellow>");
    tags.put('f', "<reset><white>");
    tags.put('k', "<obfuscated>");
    tags.put('l', "<bold>");
    tags.put('m', "<strikethrough>");
    tags.put('n', "<underlined>");
    tags.put('o', "<italic>");
    tags.put('r', "<reset>");
    return Collections.unmodifiableMap(tags);
  }
}
