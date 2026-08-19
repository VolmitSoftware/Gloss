package art.arcane.gloss.util.common;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ChatColor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class TextUtils {
  private static final Map<Character, String> LEGACY_TAGS = legacyTags();

  public static Component parse(String text) {
    return MiniMessage.miniMessage().deserialize(translateLegacy(text));
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

  private static String translateLegacy(String text) {
    if (text == null || text.isEmpty()) {
      return "";
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
    StringBuilder hex = new StringBuilder(8).append("<#");
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
    tags.put('0', "<black>");
    tags.put('1', "<dark_blue>");
    tags.put('2', "<dark_green>");
    tags.put('3', "<dark_aqua>");
    tags.put('4', "<dark_red>");
    tags.put('5', "<dark_purple>");
    tags.put('6', "<gold>");
    tags.put('7', "<gray>");
    tags.put('8', "<dark_gray>");
    tags.put('9', "<blue>");
    tags.put('a', "<green>");
    tags.put('b', "<aqua>");
    tags.put('c', "<red>");
    tags.put('d', "<light_purple>");
    tags.put('e', "<yellow>");
    tags.put('f', "<white>");
    tags.put('k', "<obfuscated>");
    tags.put('l', "<bold>");
    tags.put('m', "<strikethrough>");
    tags.put('n', "<underlined>");
    tags.put('o', "<italic>");
    tags.put('r', "<reset>");
    return Collections.unmodifiableMap(tags);
  }
}
