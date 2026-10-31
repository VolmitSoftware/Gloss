package art.arcane.gloss.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class GholoText {
  static final int EXPRESSION_LIMIT = 1024;

  private static final char ANIMATION_MARK = '%';
  private static final long TICK_MILLIS = 50L;
  private static final int MAX_ANIMATIONS = 512;
  private static final int MAX_FRAMES = 256;

  private final Map<String, String> symbols;
  private final Map<String, Animation> animations;

  private GholoText(Map<String, String> symbols, Map<String, Animation> animations) {
    this.symbols = Map.copyOf(symbols);
    this.animations = animations;
  }

  static GholoText empty() {
    return new GholoText(Map.of(), new LinkedHashMap<>());
  }

  static GholoText load(Path root, List<LegacyImportIssue> issues) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(issues, "issues");
    Map<String, String> symbols = new LinkedHashMap<>();
    Map<String, Animation> animations = new LinkedHashMap<>();
    YamlConfiguration config = optionalYaml(root.resolve("config.yml"), "config.yml", issues);
    if (config != null) {
      ConfigurationSection section = config.getConfigurationSection("Options.Symbols");
      if (section != null) {
        for (String key : section.getKeys(false)) {
          String value = section.getString(key);
          if (key.isEmpty() || value == null || value.isEmpty()) {
            continue;
          }
          symbols.put(key, value.substring(0, Character.charCount(value.codePointAt(0))));
        }
      }
    }
    YamlConfiguration animationFile = optionalYaml(root.resolve("animations.yml"), "animations.yml", issues);
    if (animationFile != null) {
      ConfigurationSection section = animationFile.getConfigurationSection("Animations");
      if (section != null) {
        for (String key : section.getKeys(false)) {
          if (animations.size() >= MAX_ANIMATIONS) {
            issues.add(warning("animations.yml", "only the first " + MAX_ANIMATIONS
                + " GHolo animations were read"));
            break;
          }
          String id = key.toLowerCase(Locale.ROOT);
          long ticks = section.getLong(key + ".ticks");
          List<String> frames = section.getStringList(key + ".content");
          if (ticks <= 0L || frames.isEmpty()) {
            issues.add(warning("animations.yml", "GHolo animation '" + key
                + "' has no frames or a tick interval below 1 and was not imported"));
            continue;
          }
          if (frames.size() > MAX_FRAMES) {
            frames = frames.subList(0, MAX_FRAMES);
            issues.add(warning("animations.yml", "GHolo animation '" + key
                + "' was cut to its first " + MAX_FRAMES + " frames"));
          }
          animations.put(id, new Animation(id, ticks, List.copyOf(frames), animations.size()));
        }
      }
    }
    return new GholoText(symbols, animations);
  }

  String apply(String content, List<String> warnings) {
    String text = replaceSymbols(content);
    if (animations.isEmpty() || text.indexOf(ANIMATION_MARK) < 0) {
      return text;
    }
    StringBuilder output = new StringBuilder(text.length() + 32);
    for (Segment segment : segments(text, Integer.MAX_VALUE)) {
      if (segment.animation() == null) {
        output.append(segment.text());
        continue;
      }
      Animation animation = segment.animation();
      String expression = expression(animation);
      if (expression == null) {
        warnings.add("GHolo animation '" + animation.id() + "' was imported as its first frame because it "
            + "cannot be written as one Gloss expression of at most " + EXPRESSION_LIMIT + " characters");
        output.append(firstFrame(animation));
        continue;
      }
      output.append("{{ ").append(expression).append(" }}");
    }
    return output.toString();
  }

  private String expression(Animation animation) {
    StringBuilder terms = new StringBuilder();
    for (String frame : animation.frames()) {
      String term = term(replaceSymbols(frame), animation.order());
      if (term == null) {
        return null;
      }
      if (!terms.isEmpty()) {
        terms.append(", ");
      }
      terms.append(term);
    }
    String expression = "select([" + terms + "], floor(time.ms / "
        + animation.ticks() * TICK_MILLIS + "))";
    return expression.length() > EXPRESSION_LIMIT ? null : expression;
  }

  private String term(String frame, int ownerOrder) {
    if (frame.contains("{{") || frame.contains("}}")) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (Segment segment : segments(frame, ownerOrder)) {
      if (segment.animation() == null) {
        parts.add(quote(segment.text()));
        continue;
      }
      String nested = expression(segment.animation());
      if (nested == null) {
        return null;
      }
      parts.add(nested);
    }
    if (parts.isEmpty()) {
      return "''";
    }
    return parts.size() == 1 ? parts.getFirst() : String.join(" + ", parts);
  }

  private String firstFrame(Animation animation) {
    StringBuilder output = new StringBuilder();
    for (Segment segment : segments(replaceSymbols(animation.frames().getFirst()), animation.order())) {
      output.append(segment.animation() == null ? segment.text() : firstFrame(segment.animation()));
    }
    return output.toString();
  }

  private List<Segment> segments(String text, int beforeOrder) {
    List<Segment> segments = new ArrayList<>();
    StringBuilder literal = new StringBuilder();
    int index = 0;
    while (index < text.length()) {
      char character = text.charAt(index);
      if (character == ANIMATION_MARK) {
        int close = text.indexOf(ANIMATION_MARK, index + 1);
        if (close > index + 1) {
          Animation animation = animations.get(text.substring(index + 1, close).toLowerCase(Locale.ROOT));
          if (animation != null && animation.order() < beforeOrder) {
            if (!literal.isEmpty()) {
              segments.add(new Segment(literal.toString(), null));
              literal.setLength(0);
            }
            segments.add(new Segment("", animation));
            index = close + 1;
            continue;
          }
        }
      }
      literal.append(character);
      index++;
    }
    if (!literal.isEmpty()) {
      segments.add(new Segment(literal.toString(), null));
    }
    return segments;
  }

  private String replaceSymbols(String text) {
    String output = text;
    for (Map.Entry<String, String> symbol : symbols.entrySet()) {
      output = output.replace(symbol.getKey(), symbol.getValue());
    }
    return output;
  }

  private static String quote(String text) {
    StringBuilder output = new StringBuilder(text.length() + 2).append('\'');
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '\\' -> output.append("\\\\");
        case '\'' -> output.append("\\'");
        case '\n' -> output.append("\\n");
        case '\r' -> {
        }
        default -> output.append(character);
      }
    }
    return output.append('\'').toString();
  }

  private static YamlConfiguration optionalYaml(Path file, String label, List<LegacyImportIssue> issues) {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    try {
      return LegacyHologramScanner.loadYaml(file);
    } catch (IOException failure) {
      issues.add(warning(label, "GHolo " + label + " could not be read: " + failure.getMessage()));
      return null;
    }
  }

  private static LegacyImportIssue warning(String id, String message) {
    return new LegacyImportIssue(LegacyImportIssue.Severity.WARNING, id, message);
  }

  private record Animation(String id, long ticks, List<String> frames, int order) {
  }

  private record Segment(String text, Animation animation) {
  }
}
