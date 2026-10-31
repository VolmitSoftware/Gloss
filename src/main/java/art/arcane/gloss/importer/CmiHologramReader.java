package art.arcane.gloss.importer;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CmiHologramReader {
  static final double DEFAULT_VIEW_RANGE = 16.0D;

  private static final double LINE_HEIGHT = 0.25D;
  private static final double GLYPH_WIDTH = 0.15D;
  private static final int DEFAULT_BACKGROUND_ALPHA = 125;
  private static final int DEFAULT_TEXT_ALPHA = 255;
  private static final String NEXT_PAGE = "!nextpage!";
  private static final Pattern ICON = Pattern.compile("(?i)^s?icon:(.+)$");
  private static final Pattern IMAGE = Pattern.compile("(?i)^img:.*");
  private static final Pattern CLICKABLE = Pattern.compile("(?is)<T>(.*?)</T>|<[CH]>.*?</[CH]>");
  private static final Pattern MATERIAL = Pattern.compile("[A-Za-z0-9_]+");
  private static final Pattern ICON_FLAG = Pattern.compile("%[A-Za-z]+(?::[^%]*)?%");
  private static final Pattern CLICK_PREFIX = Pattern.compile(
      "(?i)^click:(left|leftshift|shiftleft|right|rightshift|shiftright)!\\s*");
  private static final Pattern RUN_AS_PREFIX = Pattern.compile("(?i)^(asconsole|asplayer)!\\s*");
  private static final Pattern OTHER_PREFIX = Pattern.compile("^\\S+!(\\s|$)");
  private static final Pattern PLAIN_TEXT = Pattern.compile("\\[[0-9a-fA-F]{6}]|[&\u00a7][0-9A-Fa-fK-Ok-oRr]|\\{\\{.*?}}");

  private final String id;
  private final ConfigurationSection section;
  private final double defaultRange;
  private final List<String> warnings = new ArrayList<>();

  CmiHologramReader(String id, ConfigurationSection section, double defaultRange) {
    this.id = id;
    this.section = section;
    this.defaultRange = defaultRange;
  }

  LegacyHologramDraft draft() {
    String name = section.getString("Name", id);
    ConfigurationSection settings = section.getConfigurationSection("Settings");
    ConfigurationSection text = section.getConfigurationSection("Text");
    ConfigurationSection icon = section.getConfigurationSection("Icon");
    ConfigurationSection legacy = section.getConfigurationSection("ND");
    double[] scale = settings != null && settings.contains("Scale")
        ? vector(settings.getString("Scale"), 2, "Settings.Scale")
        : new double[] {legacyDouble(legacy, "ScaleW", 1.0D), legacyDouble(legacy, "ScaleH", 1.0D)};
    double[] direction = settings != null && settings.contains("Dir")
        ? vector(settings.getString("Dir"), 3, "Settings.Dir")
        : new double[] {legacyDouble(legacy, "Pitch", 0.0D), legacyDouble(legacy, "Yaw", 0.0D), 0.0D};
    double[] offset = settings != null && settings.contains("Offset")
        ? vector(settings.getString("Offset"), 3, "Settings.Offset") : new double[3];
    LegacyHologramDraft.LegacyLocation location = location(direction[1], direction[0]);
    LegacyHologramDraft.LegacyStyle style = new LegacyHologramDraft.LegacyStyle(
        background(text, legacy),
        textOpacity(first(text, legacy, "TAlpha") == null ? DEFAULT_TEXT_ALPHA : intValue(first(text, legacy, "TAlpha"))),
        first(text, legacy, "Shadow") == null || Boolean.TRUE.equals(first(text, legacy, "Shadow")),
        lower(stringValue(first(text, legacy, "Align"), "center")),
        lower(stringValue(first(settings, legacy, "Bill"), "center")),
        text != null && text.getBoolean("See", false),
        scaleValue(scale[0]), scaleValue(scale[1]), null,
        brightness(settings), null, null, null, null);
    if (Boolean.TRUE.equals(first(text, legacy, "Double"))) {
      warnings.add("CMI double-sided text was imported as single-sided Gloss text");
    }
    reportUnsupported(settings);

    List<String> page = firstPage(section.getStringList("Lines"));
    double lineHeight = LINE_HEIGHT * scale[1];
    LegacyHologramDraft.LegacyStyle iconStyle = iconStyle(icon, legacy, style);
    double[] iconOffset = icon != null && icon.contains("Offset")
        ? vector(icon.getString("Offset"), 3, "Icon.Offset") : new double[3];
    List<LegacyHologramDraft.LegacyRow> rows = new ArrayList<>(page.size());
    int longest = 0;
    for (int index = 0; index < page.size(); index++) {
      String line = page.get(index);
      double bottom = (page.size() - 1 - index) * lineHeight + offset[1];
      Matcher iconLine = ICON.matcher(line.strip());
      if (iconLine.matches()) {
        String material = material(iconLine.group(1));
        if (material != null) {
          rows.add(new LegacyHologramDraft.LegacyRow("item:minecraft:" + material,
              offset[0] + iconOffset[0], bottom + lineHeight / 2.0D + iconOffset[1],
              offset[2] + iconOffset[2], iconStyle));
        }
        continue;
      }
      if (IMAGE.matcher(line.strip()).matches()) {
        warnings.add("CMI image line '" + line.strip() + "' was not imported");
        continue;
      }
      String content = CmiText.translate(clickable(line), warnings);
      if (content.contains("%filler%")) {
        warnings.add("CMI %filler% padding was left as literal text");
      }
      longest = Math.max(longest, PLAIN_TEXT.matcher(content).replaceAll("").length());
      rows.add(new LegacyHologramDraft.LegacyRow(content, offset[0], bottom, offset[2], style));
    }
    if (!rows.isEmpty() && rows.stream().anyMatch(row -> row.text().startsWith("item:"))) {
      warnings.add("CMI icon lines keep their line slot, but Gloss item placement inside that slot is approximate");
    }
    LegacyHologramDraft.LegacyInteraction interaction = interaction(page.size() * lineHeight,
        Math.max(1.0D, longest * GLYPH_WIDTH * scale[0]), offset);
    String permission = permission(name, settings);
    double range = viewRange(settings);
    return new LegacyHologramDraft(name, id, location, range, permission, style, rows, interaction,
        List.copyOf(warnings));
  }

  private LegacyHologramDraft.LegacyLocation location(double yaw, double pitch) {
    String value = section.getString("Loc");
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Loc must not be blank");
    }
    String[] parts = value.split(";", -1);
    if (parts.length < 4) {
      throw new IllegalArgumentException("Loc must contain world;x;y;z");
    }
    return new LegacyHologramDraft.LegacyLocation(parts[0], number(parts[1], "Loc x"),
        number(parts[2], "Loc y"), number(parts[3], "Loc z"), yaw, pitch);
  }

  private String background(ConfigurationSection text, ConfigurationSection legacy) {
    Object color = first(text, legacy, "BColor");
    Object alpha = first(text, legacy, "BAlpha");
    String hex = color == null ? "000000" : CmiText.color(String.valueOf(color), warnings);
    if (hex == null) {
      hex = "000000";
    }
    int resolvedAlpha = alpha == null ? DEFAULT_BACKGROUND_ALPHA : Math.clamp(intValue(alpha), 0, 255);
    return "#" + hex + String.format(Locale.ROOT, "%02x", resolvedAlpha);
  }

  private static Integer textOpacity(int alpha) {
    int clamped = Math.clamp(alpha, 0, 255);
    return Math.clamp((int) Math.round((255 - clamped) * 100.0D / 231.0D), 0, 100);
  }

  private Integer brightness(ConfigurationSection settings) {
    if (settings == null || !settings.contains("Light")) {
      return null;
    }
    double[] light = vector(settings.getString("Light"), 2, "Settings.Light");
    if (light[0] < 0.0D) {
      return null;
    }
    if (light[1] >= 0.0D && light[1] != 15.0D) {
      warnings.add("CMI sky light " + (int) light[1] + " was imported as Gloss's fixed sky light of 15");
    }
    return (int) light[0];
  }

  private LegacyHologramDraft.LegacyStyle iconStyle(ConfigurationSection icon, ConfigurationSection legacy,
                                                    LegacyHologramDraft.LegacyStyle text) {
    double[] scale;
    if (icon != null && icon.contains("Scale")) {
      scale = vector(icon.getString("Scale"), 3, "Icon.Scale");
    } else {
      double uniform = legacyDouble(legacy, "IScale", 1.0D);
      scale = new double[] {uniform, uniform, uniform};
    }
    String billboard = icon != null && icon.contains("Bill") ? lower(icon.getString("Bill")) : text.billboard();
    return new LegacyHologramDraft.LegacyStyle(null, null, null, null, billboard, null,
        scaleValue(scale[0]), scaleValue(scale[1]), scaleValue(scale[2]), text.brightness(),
        null, null, null, null);
  }

  private LegacyHologramDraft.LegacyInteraction interaction(double textHeight, double textWidth,
                                                            double[] offset) {
    ConfigurationSection interaction = section.getConfigurationSection("Interaction");
    boolean enabled = interaction != null
        ? interaction.getBoolean("Enabled", true)
        : section.getBoolean("Interactable", false);
    List<String> commands = interaction != null && interaction.contains("Commands")
        ? interaction.getStringList("Commands") : section.getStringList("Commands");
    if (!enabled || commands.isEmpty()) {
      return null;
    }
    List<LegacyHologramDraft.LegacyAction> actions = new ArrayList<>();
    for (String command : commands) {
      LegacyHologramDraft.LegacyAction action = action(command);
      if (action != null) {
        actions.add(action);
      }
    }
    if (actions.isEmpty()) {
      return null;
    }
    double width = textWidth;
    double height = Math.max(textHeight, LINE_HEIGHT);
    double[] shift = new double[3];
    if (interaction != null && interaction.contains("Dimension")) {
      double[] dimension = vector(interaction.getString("Dimension"), 2, "Interaction.Dimension");
      width = dimension[0] > 0.0D ? dimension[0] : width;
      height = dimension[1] > 0.0D ? dimension[1] : height;
    }
    if (interaction != null && interaction.contains("Offset")) {
      shift = vector(interaction.getString("Offset"), 3, "Interaction.Offset");
    }
    return new LegacyHologramDraft.LegacyInteraction(width, height, offset[0] + shift[0],
        offset[1] + textHeight / 2.0D + shift[1], offset[2] + shift[2], actions);
  }

  private LegacyHologramDraft.LegacyAction action(String source) {
    String command = source == null ? "" : source.strip();
    String trigger = null;
    Matcher click = CLICK_PREFIX.matcher(command);
    if (click.find()) {
      trigger = switch (click.group(1).toLowerCase(Locale.ROOT)) {
        case "left" -> "left_click";
        case "right" -> "right_click";
        case "leftshift", "shiftleft" -> "shift_left_click";
        default -> "shift_right_click";
      };
      command = command.substring(click.end());
    }
    LegacyHologramDraft.LegacyAction.Kind kind = LegacyHologramDraft.LegacyAction.Kind.SERVER_COMMAND;
    Matcher runAs = RUN_AS_PREFIX.matcher(command);
    if (runAs.find()) {
      if (runAs.group(1).equalsIgnoreCase("asplayer")) {
        kind = LegacyHologramDraft.LegacyAction.Kind.PLAYER_COMMAND;
      }
      command = command.substring(runAs.end());
    }
    if (OTHER_PREFIX.matcher(command).find() || command.contains("[prevPage]")
        || command.contains("[nextPage]")) {
      warnings.add("CMI hologram command '" + source + "' uses CMI-only syntax and was skipped");
      return null;
    }
    command = command.replace("[playerName]", "%player%").strip();
    if (command.startsWith("/")) {
      command = command.substring(1);
    }
    return command.isEmpty() ? null : new LegacyHologramDraft.LegacyAction(kind, command, trigger);
  }

  private String permission(String name, ConfigurationSection settings) {
    boolean required = settings != null ? settings.getBoolean("Perm", false)
        : section.getBoolean("RequiresPermission", false);
    if (!required) {
      return null;
    }
    String node = "cmi.hologram." + name.toLowerCase(Locale.ROOT);
    if (!node.matches("[a-z0-9_.-]+")) {
      warnings.add("CMI permission requirement was dropped because '" + node + "' is not a valid permission node");
      return null;
    }
    return node;
  }

  private double viewRange(ConfigurationSection settings) {
    if (settings != null && settings.contains("Range.Visual")) {
      return settings.getDouble("Range.Visual");
    }
    return section.contains("Range") && !section.isConfigurationSection("Range")
        ? section.getDouble("Range") : defaultRange;
  }

  private List<String> firstPage(List<String> lines) {
    List<String> page = new ArrayList<>();
    for (String line : lines) {
      if (line.strip().equalsIgnoreCase(NEXT_PAGE)) {
        warnings.add("only the first page of the CMI hologram was imported");
        break;
      }
      page.add(line);
    }
    if (page.size() > LegacyHologramScanner.MAX_ROWS_PER_HOLOGRAM) {
      throw new IllegalArgumentException("hologram contains more than "
          + LegacyHologramScanner.MAX_ROWS_PER_HOLOGRAM + " rows");
    }
    return page;
  }

  private String clickable(String line) {
    Matcher matcher = CLICKABLE.matcher(line);
    if (!matcher.find()) {
      return line;
    }
    warnings.add("CMI clickable text was imported as plain text; its commands and hover text were not");
    return matcher.reset().replaceAll(match -> match.group(1) == null ? ""
        : Matcher.quoteReplacement(match.group(1)));
  }

  private String material(String payload) {
    String value = ICON_FLAG.matcher(payload).replaceAll("").strip();
    if (!value.equals(payload.strip())) {
      warnings.add("CMI icon flags in '" + payload.strip() + "' were not imported");
    }
    int amount = value.lastIndexOf('-');
    if (amount > 0 && value.substring(amount + 1).matches("\\d+")) {
      value = value.substring(0, amount);
    }
    int data = value.indexOf(':');
    if (data > 0 && value.substring(data + 1).matches("\\d+")) {
      value = value.substring(0, data);
    }
    if (!MATERIAL.matcher(value).matches()) {
      warnings.add("CMI icon '" + payload.strip() + "' is not a plain material and was not imported");
      return null;
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private void reportUnsupported(ConfigurationSection settings) {
    ConfigurationSection board = section.getConfigurationSection("Board");
    if (board != null && board.getBoolean("Enabled", false)) {
      warnings.add("CMI board backgrounds were not imported");
    }
    ConfigurationSection animation = section.getConfigurationSection("Animation");
    if (animation != null && !animation.getKeys(false).isEmpty()) {
      warnings.add("CMI fade and auto-rotate animation was not imported");
    }
    ConfigurationSection pages = section.getConfigurationSection("Page");
    if (pages != null && pages.getBoolean("Auto", false)) {
      warnings.add("CMI automatic page buttons were not imported");
    }
    if (settings != null && settings.getBoolean("LOS", false)) {
      warnings.add("CMI line-of-sight hiding was not imported");
    }
  }

  private static Object first(ConfigurationSection preferred, ConfigurationSection fallback, String key) {
    if (preferred != null && preferred.contains(key)) {
      return preferred.get(key);
    }
    return fallback != null && fallback.contains(key) ? fallback.get(key) : null;
  }

  private static double legacyDouble(ConfigurationSection legacy, String key, double fallback) {
    return legacy != null && legacy.contains(key) ? legacy.getDouble(key) : fallback;
  }

  private static double[] vector(String value, int size, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    String[] parts = value.split(";", -1);
    if (parts.length < size) {
      throw new IllegalArgumentException(field + " must contain " + size + " numbers");
    }
    double[] output = new double[size];
    for (int index = 0; index < size; index++) {
      output[index] = number(parts[index], field);
    }
    return output;
  }

  private static double number(String value, String field) {
    try {
      double parsed = Double.parseDouble(value.strip().replace(',', '.'));
      if (!Double.isFinite(parsed)) {
        throw new IllegalArgumentException(field + " must be finite");
      }
      return parsed;
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException(field + " must be a number");
    }
  }

  private static Double scaleValue(double value) {
    return value == 1.0D ? null : value;
  }

  private static int intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    return (int) number(String.valueOf(value), "value");
  }

  private static String stringValue(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }

  private static String lower(String value) {
    return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
  }
}
