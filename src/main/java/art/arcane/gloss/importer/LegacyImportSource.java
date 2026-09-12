package art.arcane.gloss.importer;

import java.util.List;
import java.util.Locale;

public enum LegacyImportSource {
  GHOLO("gholo", List.of("gholo", "files")),
  DECENT_HOLOGRAMS("decent-holograms", List.of("decent", "decent-holograms", "decent_holograms")),
  HOLOGRAPHIC_DISPLAYS("holographic-displays", List.of("hd", "holographic-displays", "holographic_displays")),
  FANCY_HOLOGRAMS("fancy-holograms", List.of("fancy", "fancy-holograms", "fancy_holograms")),
  FEATHERBOARD("featherboard", List.of("featherboard", "feather", "feather-board")),
  ANIMATED_SCOREBOARD("animated-scoreboard", List.of("animated-scoreboard", "animatedscoreboard", "as")),
  TAB_HEADER_FOOTER("tab", List.of("tab", "tab-header-footer", "tab_header_footer"));

  private final String id;
  private final List<String> aliases;

  LegacyImportSource(String id, List<String> aliases) {
    this.id = id;
    this.aliases = aliases;
  }

  public String id() {
    return id;
  }

  public static LegacyImportSource parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("legacy import source must not be blank");
    }
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    for (LegacyImportSource source : values()) {
      if (source.aliases.contains(normalized)) {
        return source;
      }
    }
    throw new IllegalArgumentException("unknown legacy import source: " + value);
  }

  public static List<String> suggestions() {
    return List.of("gholo", "decent-holograms", "holographic-displays", "fancy-holograms",
        "featherboard", "animated-scoreboard", "tab");
  }
}
