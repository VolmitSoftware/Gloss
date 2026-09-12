package art.arcane.gloss.locale;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.LanguageReferenceRenderer;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.format.ColorFormatter;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.io.FolderWatcher;
import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GlossLocalization implements AutoCloseable {
  private static final long MAX_LANGUAGE_BYTES = 2L * 1024L * 1024L;
  private static final long CONTENT_RECONCILIATION_NANOS = TimeUnit.SECONDS.toNanos(9L);
  private static final MessageCatalog CATALOG = GlossMessages.catalog();

  private volatile File languageFile;
  private final File dataFolder;
  private volatile PluginLanguageService languages;
  private final Logger logger;
  private final LocalizationManager manager;
  private final LongSupplier clock;
  private volatile FileWatcher watcher;
  private volatile String configuredLocale;
  private volatile String activeLocale;
  private final Map<String, String> observedHashes = new HashMap<>();
  private final Map<String, LanguageSnapshot> pendingAutomaticSnapshots = new HashMap<>();
  private final Set<String> installedLocales = new HashSet<>();
  private final Set<String> pendingDeletedLocales = new HashSet<>();
  private volatile long nextContentReconciliationNanos;

  public GlossLocalization(File dataFolder, Logger logger, String configuredLocale) {
    this(dataFolder, logger, configuredLocale, System::nanoTime);
  }

  GlossLocalization(File dataFolder, Logger logger, String configuredLocale, LongSupplier clock) {
    this.dataFolder = dataFolder;
    this.logger = logger;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, PluralSelector.oneOther()));
    this.configuredLocale = normalizeLocale(configuredLocale);
    this.languageFile = localePath(this.configuredLocale).toFile();
    this.activeLocale = CATALOG.englishLocale();
    ensureEnglishFile();
    reload();
    if (this.watcher == null) {
      this.watcher = new FolderWatcher(languageFile.getParentFile());
    }
    this.nextContentReconciliationNanos = this.clock.getAsLong() + CONTENT_RECONCILIATION_NANOS;
  }

  public String activeLocale() {
    return activeLocale;
  }

  public File languageFile() {
    return languageFile;
  }

  public PluginLanguageService enableLanguages(Gloss plugin) {
    languages = new PluginLanguageService(new PluginLanguageService.Options(
        dataFolder.toPath().resolve("languages/language-preferences.properties"),
        VolmitLocales::all,
        () -> configuredLocale,
        manager::snapshot,
        this::loadSelectedSnapshot,
        (locale, prepared) -> plugin.selectLanguage(locale, prepared),
        logger));
    return languages;
  }

  public void reloadConfigured(String locale) {
    PluginLanguageService service = languages;
    if (service != null) {
      service.selectDefault(locale).exceptionally(failure -> {
        logger.log(Level.SEVERE, "Could not load Gloss language " + locale, failure);
        return null;
      });
    }
  }

  public synchronized void install(String locale, LocalizationSnapshot snapshot) {
    installedLocales.add(locale);
    manager.install(snapshot);
    configuredLocale = locale;
    activeLocale = locale;
    watchLocale(locale);
  }

  LocalizationSnapshot snapshot() {
    PluginLanguageService service = languages;
    if (service == null) {
      return manager.snapshot();
    }
    return service.snapshot();
  }

  public boolean update() {
    PluginLanguageService service = languages;
    try {
      return service == null ? updateInstalledLanguages() : service.commitUpdate(this::updateInstalledLanguages);
    } catch (IOException failure) {
      throw new IllegalStateException("Could not capture stable Gloss language snapshots", failure);
    }
  }

  private synchronized boolean updateInstalledLanguages() throws IOException {
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
    if (!watcherChanged && pendingAutomaticSnapshots.isEmpty() && pendingDeletedLocales.isEmpty() && !reconciliationDue) {
      return false;
    }
    boolean applied = false;
    Set<String> presentLocales = new HashSet<>();
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(languageFile.toPath().getParent(), "*.toml")) {
      for (Path path : paths) {
        String name = path.getFileName().toString();
        String locale = name.substring(0, name.length() - 5);
        if (!locale.matches("[A-Za-z0-9_-]{2,32}")) {
          continue;
        }
        presentLocales.add(locale);
        installedLocales.add(locale);
        pendingDeletedLocales.remove(locale);
        LanguageSnapshot snapshot = captureSnapshot(path.toFile());
        if (snapshot == null || Objects.equals(snapshot.sha256(), observedHashes.get(locale))) {
          pendingAutomaticSnapshots.remove(locale);
          continue;
        }
        LanguageSnapshot pending = pendingAutomaticSnapshots.get(locale);
        if (pending == null || !pending.sha256().equals(snapshot.sha256())) {
          pendingAutomaticSnapshots.put(locale, snapshot);
          continue;
        }
        pendingAutomaticSnapshots.remove(locale);
        applied |= reload(snapshot, locale);
      }
    }
    Iterator<String> installed = installedLocales.iterator();
    while (installed.hasNext()) {
      String locale = installed.next();
      if (presentLocales.contains(locale)) {
        continue;
      }
      pendingAutomaticSnapshots.remove(locale);
      if (pendingDeletedLocales.add(locale)) {
        continue;
      }
      LocalizationSnapshot english = LocalizationSnapshot.create(
          LocalizationCandidate.english(CATALOG, PluralSelector.oneOther()));
      if (locale.equals(configuredLocale)) {
        manager.install(english);
      }
      if (languages != null) {
        languages.cache(locale, english);
      }
      observedHashes.remove(locale);
      pendingDeletedLocales.remove(locale);
      installed.remove();
      applied = true;
    }
    return applied;
  }

  @Override
  public void close() {
    PluginLanguageService service = languages;
    if (service != null) {
      service.close();
    }
    synchronized (this) {
      languages = null;
      FileWatcher previous = watcher;
      watcher = null;
      if (previous != null) {
        previous.close();
      }
      pendingAutomaticSnapshots.clear();
      observedHashes.clear();
      installedLocales.clear();
      pendingDeletedLocales.clear();
    }
  }

  public boolean reload() {
    PluginLanguageService service = languages;
    try {
      return service == null ? reloadDefault() : service.commitUpdate(this::reloadDefault);
    } catch (IOException failure) {
      logger.log(Level.SEVERE, "Language reload failed", failure);
      return false;
    }
  }

  private synchronized boolean reloadDefault() {
    ensureEnglishFile();
    if (!languageFile.exists()) {
      try {
        install(configuredLocale, loadSelectedSnapshot(configuredLocale));
        return true;
      } catch (Exception failure) {
        logger.log(Level.SEVERE, "Language reload failed for " + configuredLocale, failure);
        return false;
      }
    }

    LanguageSnapshot snapshot;
    try {
      snapshot = captureSnapshot(languageFile);
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

  public boolean selectLocale(String locale) {
    PluginLanguageService service = languages;
    try {
      return service == null ? reloadSelectedLocale(locale) : service.commitUpdate(() -> reloadSelectedLocale(locale));
    } catch (IOException failure) {
      logger.log(Level.SEVERE, "Language selection failed for " + locale, failure);
      return false;
    }
  }

  private synchronized boolean reloadSelectedLocale(String locale) {
    configuredLocale = normalizeLocale(locale);
    watchLocale(configuredLocale);
    return reloadDefault();
  }

  private synchronized boolean reload(LanguageSnapshot snapshot, String locale) {
    installedLocales.add(locale);
    observedHashes.put(locale, snapshot.sha256());
    LocalizationSnapshot prepared;
    try {
      prepared = LocalizationSnapshot.create(new LocalizationCandidate(CATALOG,
          List.of(parseLanguageOverlay(snapshot.rawContent(), localePath(locale).toString(), locale)),
          PluralSelector.oneOther()));
    } catch (IOException | RuntimeException failure) {
      logger.log(Level.SEVERE, "Rejected Gloss language reload for " + locale + "; keeping its last valid messages.", failure);
      return false;
    }
    if (locale.equals(configuredLocale)) {
      manager.install(prepared);
      activeLocale = locale;
    }
    if (languages != null) {
      languages.cache(locale, prepared);
    }
    return true;
  }

  private LanguageSnapshot captureSnapshot(File source) throws IOException {
    if (!source.isFile()) {
      return null;
    }
    BasicFileAttributes before = Files.readAttributes(
        source.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()) {
      return null;
    }
    if (before.size() > MAX_LANGUAGE_BYTES) {
      throw new IOException("Language source is too large: " + source.getPath());
    }
    byte[] content;
    try (InputStream input = Files.newInputStream(source.toPath())) {
      content = input.readNBytes((int) MAX_LANGUAGE_BYTES + 1);
    }
    if (content.length > MAX_LANGUAGE_BYTES) {
      throw new IOException("Language source is too large: " + source.getPath());
    }
    BasicFileAttributes after = Files.readAttributes(
        source.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!sameSnapshot(before, after) || content.length != after.size()) {
      throw new IOException("Language source changed while it was being captured: " + source.getPath());
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
    return render(snapshot().resolve(key, arguments), false);
  }

  public String legacy(TextKey key) {
    return legacy(key, MessageArgs.empty());
  }

  public String legacy(TextKey key, MessageArgs arguments) {
    return render(snapshot().resolve(key, arguments), true);
  }

  public ComponentText component(TextKey key) {
    return component(key, MessageArgs.empty());
  }

  public ComponentText component(TextKey key, MessageArgs arguments) {
    return renderComponent(snapshot().resolve(key, arguments));
  }

  public void send(CommandSender sender, TextKey key) {
    send(sender, key, MessageArgs.empty());
  }

  public void send(CommandSender sender, TextKey key, MessageArgs arguments) {
    LanguageAudience.run(sender instanceof Player player ? player.getUniqueId() : null,
        () -> ComponentMessenger.send(sender, component(key, arguments)));
  }

  public DirectorTextResolver directorResolver() {
    return (key, arguments) -> {
      MessageKey definition = CATALOG.key(key.id());
      if (!(definition instanceof TextKey textKey)) {
        return DirectorTextResolver.ENGLISH.resolve(key, arguments);
      }
      String rendered = ColorFormatter.translateColors(text(textKey, arguments));
      String plain = ColorFormatter.stripColor(rendered);
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

  public static ComponentText globalComponent(TextKey key) {
    return globalComponent(key, MessageArgs.empty());
  }

  public static ComponentText globalComponent(TextKey key, MessageArgs arguments) {
    Gloss plugin = Gloss.instance;
    GlossLocalization localization = plugin == null ? null : plugin.getLocalization();
    return localization == null
        ? renderTemplateComponent(key.english(), arguments)
        : localization.component(key, arguments);
  }

  public static void sendGlobal(CommandSender sender, TextKey key) {
    sendGlobal(sender, key, MessageArgs.empty());
  }

  public static void sendGlobal(CommandSender sender, TextKey key, MessageArgs arguments) {
    LanguageAudience.run(sender instanceof Player player ? player.getUniqueId() : null,
        () -> ComponentMessenger.send(sender, globalComponent(key, arguments)));
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
    String rendered = ColorFormatter.translateColors(renderEnglish(textKey, arguments, false));
    String plain = ColorFormatter.stripColor(rendered);
    return plain == null ? DirectorTextResolver.ENGLISH.resolve(key, arguments) : plain;
  }

  public static DirectorTextResolver globalDirectorResolver() {
    return GlossLocalization::globalDirectorText;
  }

  public PluginLanguageEditor.Options editorOptions() {
    return new PluginLanguageEditor.Options(this::loadSelectedSnapshot, this::saveEditor);
  }

  private synchronized LocalizationSnapshot saveEditor(PluginLanguageEditor.Edit edit) throws Exception {
    LocaleOverlay proposed = LocaleOverlay.builder("language editor", edit.locale())
        .put(edit.key(), edit.value()).build();
    LocalizationSnapshot.create(new LocalizationCandidate(CATALOG, List.of(proposed), PluralSelector.oneOther()));
    Path path = localePath(edit.locale());
    LocalizationSnapshot prepared = LanguageFileEditor.update(path, raw -> {
      LocalizationSnapshot current = LocalizationSnapshot.create(new LocalizationCandidate(CATALOG,
          List.of(parseLanguageOverlay(raw, path.toString(), edit.locale())), PluralSelector.oneOther()));
      MessageKey key = CATALOG.key(edit.key());
      if (key == null || !current.value(key).equals(edit.expected())) {
        throw new IOException("Language message changed while it was being edited: " + edit.key());
      }
      String updatedContent = TomlLanguageEditor.upsert(raw, edit.key(), edit.value()).content();
      LocalizationSnapshot updated = LocalizationSnapshot.create(new LocalizationCandidate(CATALOG,
          List.of(parseLanguageOverlay(updatedContent, path.toString(), edit.locale())), PluralSelector.oneOther()));
      return new LanguageFileEditor.Prepared<>(updatedContent, updated);
    });
    if (configuredLocale.equals(edit.locale())) {
      manager.install(prepared);
    }
    return prepared;
  }

  private Path localePath(String locale) {
    if (!locale.matches("[A-Za-z0-9_-]{2,32}")) {
      throw new IllegalArgumentException("Invalid language locale: " + locale);
    }
    return dataFolder.toPath().resolve("languages").resolve(locale + ".toml");
  }

  private LocaleOverlay loadLanguageOverlay(String locale) throws Exception {
    if (!locale.matches("[A-Za-z0-9_-]{2,32}")) {
      throw new IllegalArgumentException("Invalid language locale: " + locale);
    }
    Path file = dataFolder.toPath().resolve("languages").resolve(locale + ".toml");
    if (!Files.isRegularFile(file) && VolmitLocales.isBundled(locale)) {
      throw new IOException("Language file is not installed: " + locale);
    }
    if (!Files.isRegularFile(file)) {
      return null;
    }
    if (Files.size(file) > MAX_LANGUAGE_BYTES) {
      logger.warning("Using English for language file exceeding the size limit: " + file);
      return null;
    }
    return parseRuntimeLanguageOverlay(Files.readString(file), file.toString(), locale);
  }

  LocalizationSnapshot loadSelectedSnapshot(String locale) throws Exception {
    Path file = dataFolder.toPath().resolve("languages").resolve(locale + ".toml");
    if (!VolmitLocales.ENGLISH.equals(locale) && !Files.isRegularFile(file) && VolmitLocales.isBundled(locale)) {
      try (RemoteLanguageCatalog remote = RemoteLanguageCatalog.load(new RemoteLanguageCatalog.Options(
          "Gloss",
          URI.create("https://raw.githubusercontent.com/VolmitSoftware/Gloss/"),
          "src/main/resources/languages", ".toml", "gloss-language-source.properties",
          GlossLocalization.class.getClassLoader()))) {
        remote.readOrInstall(locale, file, (selected, content) ->
            LocalizationSnapshot.create(new LocalizationCandidate(CATALOG,
                List.of(parseRuntimeLanguageOverlay(content, file.toString(), selected)), PluralSelector.oneOther())));
      }
    }
    ensureEnglishFile();
    LocaleOverlay overlay = loadLanguageOverlay(locale);
    LocalizationSnapshot prepared = LocalizationSnapshot.create(new LocalizationCandidate(CATALOG,
        overlay == null ? List.of() : List.of(overlay), PluralSelector.oneOther()));
    synchronized (this) {
      installedLocales.add(locale);
    }
    return prepared;
  }

  private LocaleOverlay parseRuntimeLanguageOverlay(String content, String source, String locale) {
    try {
      return parseLanguageOverlay(content, source, locale);
    } catch (Exception failure) {
      logger.log(Level.WARNING, "Using English for unreadable language file " + source, failure);
      return LocaleOverlay.builder(source, locale).build();
    }
  }

  private LocaleOverlay parseLanguageOverlay(String content, String source, String locale) throws IOException {
    LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
    for (Map.Entry<String, MessageValue> entry : TomlLanguageParser.parseValidValues(content, CATALOG).entrySet()) {
      overlay.put(entry.getKey(), entry.getValue());
    }
    return overlay.build();
  }

  private void ensureEnglishFile() {
    Path path = localePath(VolmitLocales.ENGLISH);
    if (Files.exists(path)) {
      return;
    }
    try {
      Files.createDirectories(path.getParent());
      AtomicFileIO.writeString(path, LanguageReferenceRenderer.render(CATALOG, GlossLanguageReference.header()));
    } catch (IOException failure) {
      logger.log(Level.SEVERE, "Unable to create the English language file", failure);
    }
  }

  private void watchLocale(String locale) {
    languageFile = localePath(locale).toFile();
    observedHashes.remove(locale);
    pendingAutomaticSnapshots.remove(locale);
  }

  private static String normalizeLocale(String locale) {
    return locale == null || locale.isBlank() ? VolmitLocales.ENGLISH : locale.trim();
  }

  private static String render(ResolvedText resolved, boolean legacy) {
    return renderTemplate(resolved.template(), resolved.arguments(), legacy);
  }

  private static ComponentText renderComponent(ResolvedText resolved) {
    return renderTemplateComponent(resolved.template(), resolved.arguments());
  }

  private static String renderEnglish(TextKey key, MessageArgs arguments, boolean legacy) {
    return renderTemplate(key.english(), arguments, legacy);
  }

  private static ComponentText renderTemplateComponent(String template, MessageArgs arguments) {
    Collection<MessageArgument> values = arguments.arguments().values();
    if (values.isEmpty()) {
      return ComponentText.legacy(template);
    }

    List<Placeholder> placeholders = new ArrayList<>(values.size());
    for (MessageArgument argument : values) {
      placeholders.add(new Placeholder("{" + argument.name() + "}", argument));
    }

    ComponentText output = ComponentText.empty();
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

      output = appendComponentLiteral(output, template, copied, cursor);
      output = appendComponentArgument(output, match.argument());
      copied = cursor + match.token().length();
      cursor = template.indexOf('{', copied);
    }

    return appendComponentLiteral(output, template, copied, template.length());
  }

  private static ComponentText appendComponentLiteral(
      ComponentText output,
      String template,
      int from,
      int to) {
    if (from >= to) {
      return output;
    }
    return output.append(ComponentText.legacy(template.substring(from, to)));
  }

  private static ComponentText appendComponentArgument(ComponentText output, MessageArgument argument) {
    String value = String.valueOf(argument.value());
    if (argument.kind() == MessageArgumentKind.TRUSTED) {
      return output.append(ComponentText.legacy(value));
    }
    return output.append(ComponentText.literal(sanitizeUntrusted(value)));
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
      return legacy ? ColorFormatter.translateColors(template) : template;
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
    output.append(legacy ? ColorFormatter.translateColors(segment) : segment);
  }

  private static void appendArgument(StringBuilder output, MessageArgument argument, boolean legacy) {
    String value = String.valueOf(argument.value());
    if (argument.kind() == MessageArgumentKind.TRUSTED) {
      output.append(legacy ? ColorFormatter.translateColors(value) : value);
      return;
    }
    output.append(sanitizeUntrusted(value));
  }

  private static String sanitizeUntrusted(String value) {
    String stripped = ColorFormatter.stripColor(value);
    return stripped == null ? "" : stripped.replace("§", "");
  }

  private record Placeholder(String token, MessageArgument argument) {
  }

  private record LanguageSnapshot(String rawContent, String sha256) {
  }
}
