package art.arcane.gloss.locale;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeMessages;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import org.bukkit.ChatColor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.TomlLanguageWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlossLocalizationTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final List<LogRecord> logRecords = new ArrayList<>();
  private GlossLocalization localization;

  @Before
  public void setUp() throws Exception {
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.addHandler(new Handler() {
      @Override
      public void publish(LogRecord record) {
        logRecords.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    });
    File dataFolder = temporaryFolder.newFolder();
    Path installed = Files.createDirectories(dataFolder.toPath().resolve("languages"));
    for (String locale : VolmitLocales.nonEnglish()) {
      Files.copy(Path.of("src/main/resources/languages", locale + ".toml"), installed.resolve(locale + ".toml"));
    }
    localization = new GlossLocalization(dataFolder, logger, VolmitLocales.ENGLISH);
  }

  @After
  public void tearDown() {
    localization.close();
  }

  @Test
  public void editorPersistsEnglishAndPreservesOtherMessages() throws Exception {
    Map<String, String> english = loadLanguageFile();

    english.put(GlossMessages.MENU_CLOSED.id(), "Global closed");
    saveLanguageFile(localization.languageFile().toPath(), english);
    PluginLanguageEditor.Options editor = localization.editorOptions();
    MessageValue original = editor.loader().load("en_US").value(GlossMessages.HELP_ROOT);
    TextValue replacement = new TextValue("Edited command root");

    LocalizationSnapshot edited = editor.writer().write(new PluginLanguageEditor.Edit(
            "en_US", GlossMessages.HELP_ROOT.id(), original, replacement));

    assertEquals(replacement, edited.value(GlossMessages.HELP_ROOT));
    assertEquals(replacement, localization.snapshot().value(GlossMessages.HELP_ROOT));
    assertEquals(replacement, editor.loader().load("en_US").value(GlossMessages.HELP_ROOT));
    assertEquals("Global closed", localization.text(GlossMessages.MENU_CLOSED));
    assertTrue(Files.isRegularFile(localization.languageFile().toPath().getParent()
            .resolve("en_US.toml")));
    assertTrue(localization.reload());
    assertEquals(replacement, localization.snapshot().value(GlossMessages.HELP_ROOT));
  }

  @Test
  public void editorLeavesActiveSelectionUnchangedForAnotherLocale() throws Exception {
    Path installed = localization.languageFile().toPath().getParent().resolve("fr_FR.toml");
    Files.writeString(installed, "[command.help]\nroot = \"Racine\"\n");
    PluginLanguageEditor.Options editor = localization.editorOptions();
    MessageValue english = localization.snapshot().value(GlossMessages.HELP_ROOT);
    MessageValue french = editor.loader().load("fr_FR").value(GlossMessages.HELP_ROOT);
    TextValue replacement = new TextValue("Racine modifiee");

    editor.writer().write(new PluginLanguageEditor.Edit("fr_FR", GlossMessages.HELP_ROOT.id(), french, replacement));

    assertEquals("en_US", localization.activeLocale());
    assertEquals(english, localization.snapshot().value(GlossMessages.HELP_ROOT));
    assertEquals(replacement, editor.loader().load("fr_FR").value(GlossMessages.HELP_ROOT));
  }

  @Test
  public void editorRejectsInvalidAndStaleValuesWithoutChangingFiles() throws Exception {
    PluginLanguageEditor.Options editor = localization.editorOptions();
    MessageValue original = editor.loader().load("en_US").value(GlossMessages.HELP_ROOT);
    Path file = localization.languageFile().toPath().getParent().resolve("en_US.toml");

    assertThrows(IllegalArgumentException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
            "en_US", GlossMessages.HELP_ROOT.id(), original, new TextValue("{unexpected}"))));
    assertTrue(Files.exists(file));
    editor.writer().write(new PluginLanguageEditor.Edit(
            "en_US", GlossMessages.HELP_ROOT.id(), original, new TextValue("First edit")));
    byte[] first = Files.readAllBytes(file);
    assertThrows(IOException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
            "en_US", GlossMessages.HELP_ROOT.id(), original, new TextValue("Stale edit"))));
    assertArrayEquals(first, Files.readAllBytes(file));
  }

  @Test
  public void generatesCompleteEditableEnglishCatalog() throws Exception {
    Map<String, String> values = loadLanguageFile();

    assertEquals(GlossMessages.catalog().byId().keySet(), values.keySet());
    assertTrue(Files.readString(localization.languageFile().toPath()).contains("[command.help]"));
    assertTrue(Files.readString(localization.languageFile().toPath()).contains("languages/en_US.toml"));
    assertEquals("Open a menu by id, or show the menu list when set to *", GlossMessages.HELP_MENU_OPEN.english());
  }

  @Test
  public void englishSelectionPreservesEachLocaleFileWithoutDownloading() throws Exception {
    assertTrue(localization.selectLocale("de_DE"));
    Map<String, String> values = loadLanguageFile();
    values.put(GlossMessages.MENU_CLOSED.id(), "Locally closed");
    saveLanguageFile(localization.languageFile().toPath(), values);

    LocalizationSnapshot snapshot = localization.loadSelectedSnapshot(VolmitLocales.ENGLISH);
    localization.install(VolmitLocales.ENGLISH, snapshot);

    assertEquals(VolmitLocales.ENGLISH, localization.activeLocale());
    assertEquals(GlossMessages.HELP_MENU_OPEN.english(), localization.text(GlossMessages.HELP_MENU_OPEN));
    assertEquals(GlossMessages.MENU_CLOSED.english(), localization.text(GlossMessages.MENU_CLOSED));
    assertTrue(Files.exists(localization.languageFile().toPath().getParent().resolve("en_US.toml")));
  }

  @Test
  public void everyInstalledLocaleFullyCoversTheTypedCatalog() throws Exception {
    for (String locale : VolmitLocales.nonEnglish()) {
      assertTrue(locale, localization.selectLocale(locale));
      for (MessageKey key : localization.snapshot().catalog().keys()) {
        assertEquals(locale + ":" + key.id(), locale, localization.snapshot().sourceLocale(key));
      }
    }
  }

  @Test
  public void everyLanguageHeaderDocumentsTheCatalogVariables() throws Exception {
    Set<String> expected = new HashSet<>();
    for (MessageKey key : localization.snapshot().catalog().keys()) {
      expected.addAll(key.placeholders());
      expected.addAll(key.optionalPlaceholders());
    }
    List<String> locales = new ArrayList<>(VolmitLocales.nonEnglish());
    locales.add(VolmitLocales.ENGLISH);
    Pattern placeholder = Pattern.compile("(?<!\\{)\\{([A-Za-z][A-Za-z0-9_]*)\\}(?!\\})");
    for (String locale : locales) {
      Path path = localization.languageFile().toPath().getParent().resolve(locale + ".toml");
      String header = Files.readString(path).lines().takeWhile(line -> line.startsWith("#"))
          .collect(Collectors.joining("\n"));
      assertEquals(locale, 4L, header.lines().filter(line -> line.startsWith("# === ")).count());
      Set<String> documented = new HashSet<>();
      Matcher matcher = placeholder.matcher(header);
      while (matcher.find()) {
        documented.add(matcher.group(1));
      }
      assertEquals(locale, expected, documented);
    }
  }

  @Test
  public void repositoryMessagesUsePurpleBrandingWithDarkGreyStructure() throws Exception {
    assertTrue(GlossMessages.PERMISSION_DENIED.english().startsWith("&8[&dGloss&8]: &c"));
    assertTrue(GlossMessages.MENU_CLOSED.english().startsWith("&8[&dGloss&8]: &a"));
    assertTrue(GlossMessages.WEB_CAPABILITY_WARNING.english().startsWith("&8[&dGloss&8]: &e"));
    assertTrue(GlossMessages.PANELS_NEAR_HEADER.english().contains("&d{radius}"));
    assertTrue(GlossMessages.PANELS_NEAR_HEADER.english().contains("&d{count}"));

    for (String locale : VolmitLocales.nonEnglish()) {
      String messages = Files.readString(Path.of("src/main/resources/languages", locale + ".toml"));
      assertTrue(locale, messages.contains("&8[&dGloss&8]: "));
      assertFalse(locale, messages.contains("&7[&bGloss&7]: "));
      assertFalse(locale, messages.contains("&b{count}"));
      assertFalse(locale, messages.contains("&b{radius}"));
      assertFalse(locale, messages.contains("&b{board}"));
      assertFalse(locale, messages.contains("&b{resume}"));
      assertTrue(locale, messages.contains("&b&l"));
    }
  }

  @Test
  public void repositoryResourceSetExactlyMatchesSharedManifest() throws Exception {
    Set<String> expected = VolmitLocales.nonEnglish().stream()
        .map(locale -> locale + ".toml")
        .collect(Collectors.toUnmodifiableSet());
    try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
      Set<String> actual = paths
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .collect(Collectors.toUnmodifiableSet());
      assertEquals(expected, actual);
    }
    assertFalse(expected.contains(VolmitLocales.ENGLISH + ".toml"));
  }

  @Test
  public void builderLinkTakesItsUrlFromSettingsAndNoLocaleHardcodesOne() throws Exception {
    assertEquals("https://gloss.volmitsoftware.com", GlossConfig.current().editorSync().builderUrl());
    assertTrue(GlossMessages.WEB_OPEN.english().contains("{url}"));

    for (String locale : VolmitLocales.nonEnglish()) {
      Path resource = Path.of("src/main/resources/languages", locale + ".toml");
      String messages = Files.readString(resource);
      assertTrue(locale, messages.contains("{url}"));
      assertFalse(locale, messages.contains("holoui.volmit.com"));
      assertFalse(locale, messages.contains("holoui.volmitsoftware.com"));
      assertFalse(locale, messages.contains("gloss.volmitsoftware.com"));
    }
  }

  @Test
  public void builderUrlFallsBackWhenTheConfiguredValueIsNotAPlainLink() {
    assertEquals("https://editor.example.com/hui", GlossConfigFile.sanitizeBuilderUrl("  https://editor.example.com/hui  "));
    assertEquals("http://127.0.0.1:8080", GlossConfigFile.sanitizeBuilderUrl("http://127.0.0.1:8080"));
    assertEquals(GlossConfigFile.BUILDER_URL_DEFAULT, GlossConfigFile.sanitizeBuilderUrl(null));
    assertEquals(GlossConfigFile.BUILDER_URL_DEFAULT, GlossConfigFile.sanitizeBuilderUrl(""));
    assertEquals(GlossConfigFile.BUILDER_URL_DEFAULT, GlossConfigFile.sanitizeBuilderUrl("gloss.volmitsoftware.com"));
    assertEquals(GlossConfigFile.BUILDER_URL_DEFAULT, GlossConfigFile.sanitizeBuilderUrl("javascript:alert(1)"));
    assertEquals(GlossConfigFile.BUILDER_URL_DEFAULT, GlossConfigFile.sanitizeBuilderUrl("https://a.example'><click:run_command:/op me>"));
    assertEquals(GlossConfigFile.BUILDER_URL_DEFAULT, GlossConfigFile.sanitizeBuilderUrl("https://a.example/ b"));
  }

  @Test
  public void appliesDirectLocaleEditsWithNamedArguments() throws Exception {
    assertTrue(localization.selectLocale("fr_FR"));
    Map<String, String> values = loadLanguageFile();
    values.put(GlossMessages.MENU_UNAVAILABLE.id(), "&cMenu indisponible: {menu}");
    saveLanguageFile(localization.languageFile().toPath(), values);

    assertTrue(localization.selectLocale("fr_FR"));
    String rendered = localization.legacy(
        GlossMessages.MENU_UNAVAILABLE,
        MessageArgs.builder().untrusted("menu", "market").build()
    );

    assertEquals(ChatColor.RED + "Menu indisponible: market", rendered);
    assertEquals("fr_FR", localization.activeLocale());
  }

  @Test
  public void customLocaleUsesDirectMessagesOverEnglish() throws Exception {
    Map<String, String> values = loadLanguageFile();
    values.put(GlossMessages.MENU_UNAVAILABLE.id(), "Custom {menu}");
    saveLanguageFile(localization.languageFile().toPath().getParent().resolve("pirate_SEA.toml"), values);

    assertTrue(localization.selectLocale("pirate_SEA"));
    assertEquals("pirate_SEA", localization.activeLocale());
    assertEquals("Custom market", localization.text(
        GlossMessages.MENU_UNAVAILABLE,
        MessageArgs.builder().untrusted("menu", "market").build()
    ));
    assertEquals(GlossMessages.MENU_CLOSED.english(), localization.text(GlossMessages.MENU_CLOSED));
  }

  @Test
  public void invalidMessageFallsBackWithoutRejectingReload() throws Exception {
    Map<String, String> values = loadLanguageFile();
    values.put(GlossMessages.PREVIEW_SCALE_SIZE.id(), "Taille {percent}%");
    saveLanguageFile(localization.languageFile().toPath(), values);
    assertTrue(localization.reload());

    MessageArgs arguments = MessageArgs.builder().untrusted("percent", 125).build();
    assertEquals("Taille 125%", localization.text(GlossMessages.PREVIEW_SCALE_SIZE, arguments));

    values.put(GlossMessages.PREVIEW_SCALE_SIZE.id(), "Argument absent");
    saveLanguageFile(localization.languageFile().toPath(), values);

    assertTrue(localization.reload());
    assertEquals(GlossMessages.PREVIEW_SCALE_SIZE.english().replace("{percent}", "125"), localization.text(GlossMessages.PREVIEW_SCALE_SIZE, arguments));
  }

  @Test
  public void automaticReloadQueuesStableExactContentAndDoesNotRecreateDeletion() throws Exception {
    localization.close();
    AtomicLong clock = new AtomicLong();
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    localization = new GlossLocalization(
        temporaryFolder.newFolder("clocked-localization"), logger, VolmitLocales.ENGLISH, clock::get);
    Map<String, String> values = loadLanguageFile();
    values.put(GlossMessages.MENU_UNAVAILABLE.id(), "Alpha {menu}");
    saveLanguageFile(localization.languageFile().toPath(), values);
    MessageArgs arguments = MessageArgs.builder().untrusted("menu", "market").build();

    clock.addAndGet(TimeUnit.SECONDS.toNanos(9L));
    localization.update();
    assertFalse("Alpha market".equals(localization.text(GlossMessages.MENU_UNAVAILABLE, arguments)));
    localization.update();
    assertEquals("Alpha market", localization.text(GlossMessages.MENU_UNAVAILABLE, arguments));

    FileTime appliedTime = Files.getLastModifiedTime(localization.languageFile().toPath());
    values.put(GlossMessages.MENU_UNAVAILABLE.id(), "Bravo {menu}");
    saveLanguageFile(localization.languageFile().toPath(), values);
    Files.setLastModifiedTime(localization.languageFile().toPath(), appliedTime);

    clock.addAndGet(TimeUnit.SECONDS.toNanos(9L));
    localization.update();
    assertEquals("Alpha market", localization.text(GlossMessages.MENU_UNAVAILABLE, arguments));
    localization.update();
    assertEquals("Bravo market", localization.text(GlossMessages.MENU_UNAVAILABLE, arguments));

    Files.delete(localization.languageFile().toPath());
    clock.addAndGet(TimeUnit.SECONDS.toNanos(9L));
    localization.update();
    assertFalse(localization.languageFile().exists());
    assertEquals("Bravo market", localization.text(GlossMessages.MENU_UNAVAILABLE, arguments));
  }

  @Test
  public void resolvesDirectorLabelsAndDoesNotRenderUntrustedFormatting() throws Exception {
    Map<String, String> values = loadLanguageFile();
        values.put("director.help.navigation.back", "&aRetour");
    saveLanguageFile(localization.languageFile().toPath(), values);
    assertTrue(localization.reload());

    assertEquals("Retour", localization.directorResolver().resolve(DirectorHelpMessages.BACK));
    assertEquals(
        "Unknown parameter key: BadName",
        localization.directorResolver().resolve(
            DirectorRuntimeMessages.UNKNOWN_PARAMETER,
            MessageArgs.builder().untrusted("key", "&cBad" + ChatColor.DARK_RED + "Name").build()
        )
    );
    String rendered = localization.legacy(
        GlossMessages.MENU_UNAVAILABLE,
        MessageArgs.builder().untrusted("menu", "&cBad" + ChatColor.DARK_RED + "Name").build()
    );
    assertTrue(rendered.contains("&cBadName"));
    assertFalse(rendered.contains(String.valueOf(ChatColor.DARK_RED)));
  }

  @Test
  public void componentRenderingKeepsUntrustedColorAndMarkupLiteral() {
    ComponentText rendered = localization.component(
        GlossMessages.MENU_UNAVAILABLE,
        MessageArgs.builder().untrusted("menu", "<red>&cBad" + ChatColor.DARK_RED + "Name").build()
    );

    assertEquals("[Gloss]: \"<red>&cBadName\" is not available.", rendered.plain());
    assertTrue(rendered.legacy().contains("<red>&cBadName"));
    assertFalse(rendered.legacy().contains(ChatColor.DARK_RED + "Name"));
  }

  @Test
  public void insertedArgumentsAreNeverReprocessedAsLaterSentinels() {
    String rendered = localization.text(
        GlossMessages.PREVIEW_FUEL_LEVEL,
        MessageArgs.builder()
            .untrusted("fuel", "\uE0001\uE001")
            .untrusted("maximum", "replacement")
            .build()
    );

    assertTrue(rendered.contains("\uE0001\uE001"));
    assertTrue(rendered.contains("replacement"));
  }

  @Test
  public void zeroArgumentRenderingTranslatesTheTemplateOnlyForLegacy() {
    String english = GlossMessages.WEB_CAPABILITY_WARNING.english();

    assertEquals(english, localization.text(GlossMessages.WEB_CAPABILITY_WARNING));
    assertEquals(
        ChatColor.translateAlternateColorCodes('&', english),
        localization.legacy(GlossMessages.WEB_CAPABILITY_WARNING)
    );
  }

  @Test
  public void legacyRenderingTranslatesTemplateColorsAroundSplicedArguments() {
    assertEquals(
        ChatColor.translateAlternateColorCodes('&', "&7Page &f2&7/&f3 &8- &7showing &f13&7-&f24 &7of &f25"),
        localization.legacy(
            GlossMessages.LIST_PAGE,
            MessageArgs.builder()
                .trusted("page", 2)
                .trusted("pages", 3)
                .trusted("from", 13)
                .trusted("to", 24)
                .trusted("total", 25)
                .build()
        )
    );
  }

  @Test
  public void argumentValuesThatLookLikePlaceholdersAreNotReprocessed() {
    String rendered = localization.text(
        GlossMessages.PREVIEW_FUEL_LEVEL,
        MessageArgs.builder()
            .untrusted("fuel", "{maximum}")
            .untrusted("maximum", "9")
            .build()
    );

    assertTrue(rendered.contains("{maximum}"));
    assertTrue(rendered.contains("9"));
  }

  @Test
  public void listPagerMessagesDeclareEveryRuntimePlaceholder() {
    assertEquals(Set.of("page", "pages", "from", "to", "total"), GlossMessages.LIST_PAGE.placeholders());
    assertEquals(Set.of("command"), GlossMessages.LIST_NEXT.placeholders());
    assertEquals(
        "&7Page &f2&7/&f3 &8- &7showing &f13&7-&f24 &7of &f25",
        localization.text(
            GlossMessages.LIST_PAGE,
            MessageArgs.builder()
                .trusted("page", 2)
                .trusted("pages", 3)
                .trusted("from", 13)
                .trusted("to", 24)
                .trusted("total", 25)
                .build()
        )
    );
    assertEquals(
        "&7Next page: &f/gloss emoji list page=3",
        localization.text(
            GlossMessages.LIST_NEXT,
            MessageArgs.builder().untrusted("command", "/gloss emoji list page=3").build()
        )
    );
  }

  @Test
  public void webConsoleMessagesAcceptOnlyTheirDeclaredArguments() {
    assertEquals(Set.of(), GlossMessages.WEB_CAPABILITY_WARNING.placeholders());
    assertEquals(Set.of("subject", "url"), GlossMessages.WEB_OPEN_CONSOLE.placeholders());
    assertEquals(Set.of("session"), GlossMessages.WEB_LINK_HOVER.placeholders());
    String warning = localization.legacy(GlossMessages.WEB_CAPABILITY_WARNING);
    String open = localization.legacy(
        GlossMessages.WEB_OPEN_CONSOLE,
        MessageArgs.builder()
            .untrusted("subject", "sync-qa")
            .untrusted("url", "https://editor.example/#/sync/secret")
            .build()
    );
    assertTrue(warning.contains("Treat it as a secret"));
    assertTrue(open.contains("sync-qa"));
    assertTrue(open.contains("https://editor.example/#/sync/secret"));
    String hover = localization.text(
        GlossMessages.WEB_LINK_HOVER,
        MessageArgs.builder()
            .untrusted("session", "session12345")
            .build()
    );
    assertTrue(hover.contains("session12345"));
  }

  @Test
  public void editorUpdatesNestedRepositoryMessagesWithoutDuplicatingKeys() throws Exception {
    Path file = localization.languageFile().toPath().getParent().resolve("fr_FR.toml");
    String originalHeader = Files.readString(file).lines().takeWhile(line -> line.startsWith("#"))
        .collect(Collectors.joining("\n"));
    PluginLanguageEditor.Options editor = localization.editorOptions();
    LocalizationSnapshot original = editor.loader().load("fr_FR");
    TextValue replacement = new TextValue("Racine modifiee");
    LocalizationSnapshot edited = editor.writer().write(new PluginLanguageEditor.Edit(
        "fr_FR", GlossMessages.HELP_ROOT.id(), original.value(GlossMessages.HELP_ROOT), replacement));
    assertEquals(replacement, edited.value(GlossMessages.HELP_ROOT));
    assertEquals(replacement, editor.loader().load("fr_FR").value(GlossMessages.HELP_ROOT));
    assertEquals(original.value(GlossMessages.MENU_CLOSED), edited.value(GlossMessages.MENU_CLOSED));
    assertTrue(Files.readString(file).startsWith(originalHeader));
  }

  @Test
  public void partialLocaleKeepsValidMessagesAndFallsBackForInvalidEntries() throws Exception {
    Path file = localization.languageFile().toPath().getParent().resolve("fr_FR.toml");
    String raw = "[command.help]\nroot = \"Racine\"\nstatus = \"{broken\"\n"
        + "[gloss.message.menu]\nunavailable = \"Sans variable\"\nclosed = 42\n";
    Files.writeString(file, raw);

    LocalizationSnapshot snapshot = localization.loadSelectedSnapshot("fr_FR");

    assertEquals(new TextValue("Racine"), snapshot.value(GlossMessages.HELP_ROOT));
    assertEquals(GlossMessages.MENU_UNAVAILABLE.englishValue(), snapshot.value(GlossMessages.MENU_UNAVAILABLE));
    assertEquals(GlossMessages.MENU_CLOSED.englishValue(), snapshot.value(GlossMessages.MENU_CLOSED));
    assertEquals(GlossMessages.HELP_STATUS.englishValue(), snapshot.value(GlossMessages.HELP_STATUS));
    assertEquals(raw, Files.readString(file));
  }

  @Test
  public void editorAddsAChildMessageBesideItsTranslatedParent() throws Exception {
    Path file = localization.languageFile().toPath().getParent().resolve("fr_FR.toml");
    Files.writeString(file, "# Local messages\n[command.help]\nweb = \"Editeur web\"\n");
    PluginLanguageEditor.Options editor = localization.editorOptions();
    LocalizationSnapshot current = editor.loader().load("fr_FR");
    TextValue replacement = new TextValue("Ouvrir l'editeur");
    LocalizationSnapshot edited = editor.writer().write(new PluginLanguageEditor.Edit(
        "fr_FR", GlossMessages.HELP_WEB_OPEN.id(), current.value(GlossMessages.HELP_WEB_OPEN), replacement));
    assertEquals(new TextValue("Editeur web"), edited.value(GlossMessages.HELP_WEB));
    assertEquals(replacement, editor.loader().load("fr_FR").value(GlossMessages.HELP_WEB_OPEN));
    assertTrue(Files.readString(file).startsWith("# Local messages"));
    assertTrue(Files.readString(file).contains("\"web.open\" = "));
  }

  @Test
  public void generatedEnglishContainsTheCatalogAndPreservesDirectEdits() throws Exception {
    Path file = localization.languageFile().toPath().getParent().resolve("en_US.toml");
    LocalizationSnapshot english = localization.loadSelectedSnapshot("en_US");
    for (MessageKey key : english.catalog().keys()) {
      assertEquals(key.id(), key.englishValue(), english.value(key));
    }
    String raw = Files.readString(file);
    assertTrue(raw.contains("Prefixes"));
    assertTrue(raw.contains("{menu}"));
    Files.writeString(file, "[command.help]\nroot = \"Local English\"\n");
    assertTrue(localization.reload());
    assertEquals("Local English", localization.text(GlossMessages.HELP_ROOT));
    assertTrue(localization.reload());
    assertEquals("Local English", localization.text(GlossMessages.HELP_ROOT));
  }

  @Test
  public void malformedLocaleFallsBackToEnglish() throws Exception {
    Path file = localization.languageFile().toPath().getParent().resolve("fr_FR.toml");
    Files.writeString(file, "[command.help\n");
    LocalizationSnapshot snapshot = localization.loadSelectedSnapshot("fr_FR");
    assertEquals(GlossMessages.HELP_ROOT.englishValue(), snapshot.value(GlossMessages.HELP_ROOT));
  }

  private Map<String, String> loadLanguageFile() throws Exception {
    return TomlLanguageParser.parseText(Files.readString(localization.languageFile().toPath()));
  }

  private void saveLanguageFile(Path file, Map<String, String> values) throws IOException {
    Files.writeString(file, TomlLanguageWriter.renderText(values, List.of()));
  }

}
