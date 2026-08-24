package art.arcane.gloss.locale;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationIssue;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GlossLocalization implements AutoCloseable {
  private static final long MAX_LANGUAGE_BYTES = 2L * 1024L * 1024L;
  private static final long CONTENT_RECONCILIATION_NANOS = TimeUnit.SECONDS.toNanos(9L);
  private static final int MAX_REPORTED_ISSUES = 12;
  private static final MessageCatalog CATALOG = GlossMessages.catalog();

  private static final char YAML_PATH_SEPARATOR = '/';

  private final File languageFile;
  private final Logger logger;
  private final LocalizationManager manager;
  private final LongSupplier clock;
  private volatile FileWatcher watcher;
  private volatile String configuredLocale;
  private volatile String activeLocale;
  private volatile String observedHash;
  private volatile LanguageSnapshot pendingAutomaticSnapshot;
  private volatile long nextContentReconciliationNanos;

  public GlossLocalization(File dataFolder, Logger logger, String configuredLocale) {
    this(dataFolder, logger, configuredLocale, System::nanoTime);
  }

  GlossLocalization(File dataFolder, Logger logger, String configuredLocale, LongSupplier clock) {
    this.languageFile = new File(dataFolder, "language.yml");
    this.logger = logger;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, PluralSelector.oneOther()));
    this.configuredLocale = normalizeLocale(configuredLocale);
    this.activeLocale = CATALOG.englishLocale();
    ensureDefaultFile();
    reload();
    this.watcher = new FileWatcher(languageFile);
    this.nextContentReconciliationNanos = this.clock.getAsLong() + CONTENT_RECONCILIATION_NANOS;
  }

  public String activeLocale() {
    return activeLocale;
  }

  public File languageFile() {
    return languageFile;
  }

  LocalizationSnapshot snapshot() {
    return manager.snapshot();
  }

  public boolean update() {
    FileWatcher current = watcher;
    if (current == null) {
      return false;
    }
    boolean watcherChanged = current.checkModifiedEvents();
    long now = clock.getAsLong();
    boolean reconciliationDue = now >= nextContentReconciliationNanos;
    if (reconciliationDue) {
      nextContentReconciliationNanos = now + CONTENT_RECONCILIATION_NANOS;
    }
    if (!watcherChanged && pendingAutomaticSnapshot == null && !reconciliationDue) {
      return false;
    }
    LanguageSnapshot snapshot;
    try {
      snapshot = captureSnapshot();
    } catch (IOException failure) {
      throw new IllegalStateException("Could not capture a stable language.yml snapshot", failure);
    }
    if (snapshot == null) {
      pendingAutomaticSnapshot = null;
      return false;
    }
    if (Objects.equals(snapshot.sha256(), observedHash)) {
      pendingAutomaticSnapshot = null;
      return false;
    }
    LanguageSnapshot pending = pendingAutomaticSnapshot;
    if (pending == null || !pending.sha256().equals(snapshot.sha256())) {
      pendingAutomaticSnapshot = snapshot;
      return false;
    }
    pendingAutomaticSnapshot = null;
    return reload(snapshot, configuredLocale);
  }

  @Override
  public synchronized void close() {
    FileWatcher previous = watcher;
    watcher = null;
    if (previous != null) {
      previous.close();
    }
    pendingAutomaticSnapshot = null;
  }

  public synchronized boolean reload() {
    if (!languageFile.exists()) {
      ensureDefaultFile();
    }

    LanguageSnapshot snapshot;
    try {
      snapshot = captureSnapshot();
    } catch (IOException failure) {
      logger.log(Level.SEVERE, "Language reload failed", failure);
      return false;
    }
    if (snapshot == null) {
      logger.severe("Language reload failed: source is not a regular file: " + languageFile.getPath());
      return false;
    }
    return reload(snapshot, configuredLocale);
  }

  public synchronized boolean selectLocale(String locale) {
    configuredLocale = normalizeLocale(locale);
    return reload();
  }

  private synchronized boolean reload(LanguageSnapshot snapshot, String locale) {
    LocalizationReloadResult result = manager.reload(() -> loadCandidate(snapshot.rawContent(), locale));
    observedHash = snapshot.sha256();
    if (!result.applied()) {
      reportRejectedReload(result);
      return false;
    }

    activeLocale = result.current().overlays().isEmpty()
        ? CATALOG.englishLocale()
        : result.current().overlays().get(0).locale();
    return true;
  }

  private LanguageSnapshot captureSnapshot() throws IOException {
    if (!languageFile.isFile()) {
      return null;
    }
    BasicFileAttributes before = Files.readAttributes(
        languageFile.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()) {
      return null;
    }
    if (before.size() > MAX_LANGUAGE_BYTES) {
      throw new IOException("Language source is too large: " + languageFile.getPath());
    }
    byte[] content;
    try (InputStream input = Files.newInputStream(languageFile.toPath())) {
      content = input.readNBytes((int) MAX_LANGUAGE_BYTES + 1);
    }
    if (content.length > MAX_LANGUAGE_BYTES) {
      throw new IOException("Language source is too large: " + languageFile.getPath());
    }
    BasicFileAttributes after = Files.readAttributes(
        languageFile.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!sameSnapshot(before, after) || content.length != after.size()) {
      throw new IOException("Language source changed while it was being captured: " + languageFile.getPath());
    }
    return new LanguageSnapshot(
        new String(content, StandardCharsets.UTF_8),
        DocumentHashes.sha256(content)
    );
  }

  private boolean sameSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
    return before.isRegularFile()
        && after.isRegularFile()
        && before.size() == after.size()
        && before.lastModifiedTime().equals(after.lastModifiedTime())
        && Objects.equals(before.fileKey(), after.fileKey());
  }

  public String text(TextKey key) {
    return text(key, MessageArgs.empty());
  }

  public String text(TextKey key, MessageArgs arguments) {
    return render(manager.snapshot().resolve(key, arguments), false);
  }

  public String legacy(TextKey key) {
    return legacy(key, MessageArgs.empty());
  }

  public String legacy(TextKey key, MessageArgs arguments) {
    return render(manager.snapshot().resolve(key, arguments), true);
  }

  public DirectorTextResolver directorResolver() {
    return (key, arguments) -> {
      MessageKey definition = CATALOG.key(key.id());
      if (!(definition instanceof TextKey textKey)) {
        return DirectorTextResolver.ENGLISH.resolve(key, arguments);
      }
      String rendered = ChatColor.translateAlternateColorCodes('&', text(textKey, arguments));
      String plain = ChatColor.stripColor(rendered);
      return plain == null ? DirectorTextResolver.ENGLISH.resolve(key, arguments) : plain;
    };
  }

  public static MessageArgs args(MessageArgument... arguments) {
    MessageArgs.Builder builder = MessageArgs.builder();
    for (MessageArgument argument : arguments) {
      builder.add(argument);
    }
    return builder.build();
  }

  public static String globalText(TextKey key) {
    return globalText(key, MessageArgs.empty());
  }

  public static String globalText(TextKey key, MessageArgs arguments) {
    Gloss plugin = Gloss.instance;
    GlossLocalization localization = plugin == null ? null : plugin.getLocalization();
    return localization == null ? renderEnglish(key, arguments, false) : localization.text(key, arguments);
  }

  public static String globalLegacy(TextKey key) {
    return globalLegacy(key, MessageArgs.empty());
  }

  public static String globalLegacy(TextKey key, MessageArgs arguments) {
    Gloss plugin = Gloss.instance;
    GlossLocalization localization = plugin == null ? null : plugin.getLocalization();
    return localization == null ? renderEnglish(key, arguments, true) : localization.legacy(key, arguments);
  }

  public static String globalDirectorText(TextKey key, MessageArgs arguments) {
    Gloss plugin = Gloss.instance;
    GlossLocalization localization = plugin == null ? null : plugin.getLocalization();
    if (localization != null) {
      return localization.directorResolver().resolve(key, arguments);
    }
    MessageKey definition = CATALOG.key(key.id());
    if (!(definition instanceof TextKey textKey)) {
      return DirectorTextResolver.ENGLISH.resolve(key, arguments);
    }
    String rendered = ChatColor.translateAlternateColorCodes('&', renderEnglish(textKey, arguments, false));
    String plain = ChatColor.stripColor(rendered);
    return plain == null ? DirectorTextResolver.ENGLISH.resolve(key, arguments) : plain;
  }

  public static DirectorTextResolver globalDirectorResolver() {
    return GlossLocalization::globalDirectorText;
  }

  private LocalizationCandidate loadCandidate(String rawContent, String selectedLocale) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.options().pathSeparator(YAML_PATH_SEPARATOR);
    yaml.loadFromString(rawContent);
    if (yaml.contains("locale")) {
      throw new IllegalArgumentException(
          "language.yml no longer selects the locale; remove its top-level locale key (or delete language.yml, losing its "
              + "overrides, and restart Gloss to regenerate it), set language in plugins/Gloss/gloss.toml, then run "
              + "/gloss reload. This version does not migrate the obsolete locale key"
      );
    }
    LocaleOverlay.Builder overlay = LocaleOverlay.builder(languageFile.getPath(), selectedLocale);
    ConfigurationSection messages = yaml.getConfigurationSection("messages");
    if (messages != null) {
      appendMessages(messages, overlay);
    }

    List<LocaleOverlay> overlays = new ArrayList<>();
    overlays.add(overlay.build());
    LocaleOverlay bundled = loadBundledOverlay(selectedLocale);
    if (bundled != null) {
      overlays.add(bundled);
    }
    return new LocalizationCandidate(CATALOG, overlays, PluralSelector.oneOther());
  }

  private LocaleOverlay loadBundledOverlay(String locale) throws Exception {
    if (VolmitLocales.ENGLISH.equals(locale)) {
      return null;
    }

    String resourcePath = "/languages/" + locale + ".yml";
    InputStream input = GlossLocalization.class.getResourceAsStream(resourcePath);
    if (input == null) {
      if (VolmitLocales.isBundled(locale)) {
        throw new IllegalArgumentException("Missing bundled language resource: " + resourcePath);
      }
      return null;
    }

    try (InputStream stream = input; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.options().pathSeparator(YAML_PATH_SEPARATOR);
      yaml.load(reader);
      String declaredLocale = yaml.getString("locale");
      if (!locale.equals(declaredLocale)) {
        throw new IllegalArgumentException(resourcePath + " must declare locale: " + locale);
      }

      LocaleOverlay.Builder overlay = LocaleOverlay.builder(resourcePath, locale);
      ConfigurationSection messages = yaml.getConfigurationSection("messages");
      if (messages != null) {
        appendMessages(messages, overlay);
      }
      return overlay.build();
    }
  }

  private void appendMessages(ConfigurationSection messages, LocaleOverlay.Builder overlay) {
    Map<String, Map<String, String>> pluralForms = new LinkedHashMap<>();
    for (String path : messages.getKeys(true)) {
      if (messages.isConfigurationSection(path)) {
        continue;
      }

      Object value = messages.get(path);
      String id = path.replace(YAML_PATH_SEPARATOR, '.');
      if (!(value instanceof String template)) {
        throw new IllegalArgumentException("Language value must be text: " + id);
      }

      MessageKey key = CATALOG.key(id);
      if (key instanceof TextKey) {
        overlay.text(id, template);
        continue;
      }

      int separator = id.lastIndexOf('.');
      String pluralId = separator < 0 ? "" : id.substring(0, separator);
      if (CATALOG.key(pluralId) instanceof PluralKey) {
        String category = id.substring(separator + 1);
        pluralForms.computeIfAbsent(pluralId, ignored -> new LinkedHashMap<>()).put(category, template);
        continue;
      }

      overlay.text(id, template);
    }

    for (Map.Entry<String, Map<String, String>> entry : pluralForms.entrySet()) {
      overlay.plural(entry.getKey(), entry.getValue());
    }
  }

  private void ensureDefaultFile() {
    if (languageFile.exists()) {
      return;
    }

    try {
      Files.createDirectories(languageFile.toPath().getParent());
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.options().header(
          "Gloss message overrides. Set the active language with the leading language key in gloss.toml.\n"
              + "Add only the message keys you want to replace below messages; bundled translations and English fill the rest."
      );
      yaml.createSection("messages");
      yaml.save(languageFile);
    } catch (Exception exception) {
      logger.log(Level.SEVERE, "Unable to create the default language file", exception);
    }
  }

  private static String normalizeLocale(String locale) {
    return locale == null || locale.isBlank() ? VolmitLocales.ENGLISH : locale.trim();
  }

  private void reportRejectedReload(LocalizationReloadResult result) {
    logger.severe("Rejected language reload; continuing with " + activeLocale + ".");
    List<LocalizationIssue> issues = result.validation().errors();
    for (int index = 0; index < Math.min(issues.size(), MAX_REPORTED_ISSUES); index++) {
      LocalizationIssue issue = issues.get(index);
      logger.severe(issue.source() + " [" + issue.key() + "]: " + issue.detail());
    }
    if (issues.size() > MAX_REPORTED_ISSUES) {
      logger.severe((issues.size() - MAX_REPORTED_ISSUES) + " additional language errors were omitted.");
    }
    if (result.failure() != null) {
      logger.log(Level.SEVERE, "Language reload failed", result.failure());
    }
  }

  private static String render(ResolvedText resolved, boolean legacy) {
    return renderTemplate(resolved.template(), resolved.arguments(), legacy);
  }

  private static String renderEnglish(TextKey key, MessageArgs arguments, boolean legacy) {
    return renderTemplate(key.english(), arguments, legacy);
  }

  /**
   * Splices argument values straight into the output as the template is scanned left to right.
   * Inserted text is appended, never rescanned, so a value that itself looks like a placeholder is
   * never reprocessed. Literal segments carry the legacy colour translation; argument values keep
   * the trusted/untrusted handling.
   */
  private static String renderTemplate(String template, MessageArgs arguments, boolean legacy) {
    Collection<MessageArgument> values = arguments.arguments().values();
    if (values.isEmpty()) {
      return legacy ? ChatColor.translateAlternateColorCodes('&', template) : template;
    }

    List<Placeholder> placeholders = new ArrayList<>(values.size());
    for (MessageArgument argument : values) {
      placeholders.add(new Placeholder("{" + argument.name() + "}", argument));
    }

    StringBuilder output = new StringBuilder(template.length() + 16);
    int copied = 0;
    int cursor = template.indexOf('{');
    while (cursor >= 0) {
      Placeholder match = null;
      for (Placeholder placeholder : placeholders) {
        if (template.startsWith(placeholder.token(), cursor)) {
          match = placeholder;
          break;
        }
      }
      if (match == null) {
        cursor = template.indexOf('{', cursor + 1);
        continue;
      }

      appendLiteral(output, template, copied, cursor, legacy);
      appendArgument(output, match.argument(), legacy);
      copied = cursor + match.token().length();
      cursor = template.indexOf('{', copied);
    }

    appendLiteral(output, template, copied, template.length(), legacy);
    return output.toString();
  }

  private static void appendLiteral(StringBuilder output, String template, int from, int to, boolean legacy) {
    if (from >= to) {
      return;
    }

    String segment = template.substring(from, to);
    output.append(legacy ? ChatColor.translateAlternateColorCodes('&', segment) : segment);
  }

  private static void appendArgument(StringBuilder output, MessageArgument argument, boolean legacy) {
    String value = String.valueOf(argument.value());
    if (argument.kind() == MessageArgumentKind.TRUSTED) {
      output.append(legacy ? ChatColor.translateAlternateColorCodes('&', value) : value);
      return;
    }
    output.append(sanitizeUntrusted(value));
  }

  private static String sanitizeUntrusted(String value) {
    String stripped = ChatColor.stripColor(value);
    return stripped == null ? "" : stripped.replace(String.valueOf(ChatColor.COLOR_CHAR), "");
  }

  private record Placeholder(String token, MessageArgument argument) {
  }

  private record LanguageSnapshot(String rawContent, String sha256) {
  }
}
