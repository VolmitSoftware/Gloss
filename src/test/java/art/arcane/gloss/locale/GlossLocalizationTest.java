package art.arcane.gloss.locale;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeMessages;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    localization = new GlossLocalization(temporaryFolder.newFolder(), logger, VolmitLocales.ENGLISH);
  }

  @After
  public void tearDown() {
    localization.close();
  }

  @Test
  public void generatesSparseOverrideFileWithEnglishInTheTypedCatalog() throws Exception {
    YamlConfiguration yaml = loadLanguageFile();

    assertFalse(yaml.contains("locale"));
    assertTrue(yaml.isConfigurationSection("messages"));
    assertTrue(Files.readString(localization.languageFile().toPath()).contains("language key in gloss.toml"));
    assertEquals("Open a menu by id, or show the menu list when set to *", GlossMessages.HELP_MENU_OPEN.english());
  }

  @Test
  public void everyBundledLocaleFullyCoversTheTypedCatalog() throws Exception {
    for (String locale : VolmitLocales.nonEnglish()) {
      assertTrue(locale, localization.selectLocale(locale));
      for (MessageKey key : localization.snapshot().catalog().keys()) {
        assertEquals(locale + ":" + key.id(), locale, localization.snapshot().sourceLocale(key));
      }
    }
  }

  @Test
  public void bundledMessagesUsePurpleBrandingWithDarkGreyStructure() throws Exception {
    assertTrue(GlossMessages.PERMISSION_DENIED.english().startsWith("&8[&dGloss&8]: &c"));
    assertTrue(GlossMessages.MENU_CLOSED.english().startsWith("&8[&dGloss&8]: &a"));
    assertTrue(GlossMessages.WEB_CAPABILITY_WARNING.english().startsWith("&8[&dGloss&8]: &e"));
    assertTrue(GlossMessages.PANELS_NEAR_HEADER.english().contains("&d{radius}"));
    assertTrue(GlossMessages.PANELS_NEAR_HEADER.english().contains("&d{count}"));

    for (String locale : VolmitLocales.nonEnglish()) {
      String messages = Files.readString(Path.of("src/main/resources/languages", locale + ".yml"));
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
  public void bundledResourceSetExactlyMatchesSharedManifest() throws Exception {
    Set<String> expected = VolmitLocales.nonEnglish().stream()
        .map(locale -> locale + ".yml")
        .collect(Collectors.toUnmodifiableSet());
    try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
      Set<String> actual = paths
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .collect(Collectors.toUnmodifiableSet());
      assertEquals(expected, actual);
    }
    assertFalse(expected.contains(VolmitLocales.ENGLISH + ".yml"));
  }

  @Test
  public void builderLinkTakesItsUrlFromSettingsAndNoLocaleHardcodesOne() throws Exception {
    assertEquals("https://gloss.volmitsoftware.com", GlossConfig.current().editorSync().builderUrl());
    assertTrue(GlossMessages.WEB_OPEN.english().contains("{url}"));

    for (String locale : VolmitLocales.nonEnglish()) {
      Path resource = Path.of("src/main/resources/languages", locale + ".yml");
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
  public void appliesExternalOverrideWithNamedArguments() throws Exception {
    YamlConfiguration yaml = loadLanguageFile();
    yaml.set("messages." + GlossMessages.MENU_UNAVAILABLE.id(), "&cMenu indisponible: {menu}");
    yaml.save(localization.languageFile());

    assertTrue(localization.selectLocale("fr_FR"));
    String rendered = localization.legacy(
        GlossMessages.MENU_UNAVAILABLE,
        MessageArgs.builder().untrusted("menu", "market").build()
    );

    assertEquals(ChatColor.RED + "Menu indisponible: market", rendered);
    assertEquals("fr_FR", localization.activeLocale());
  }

  @Test
  public void customLocaleUsesOverridesOverEnglishWithoutABundledCatalog() throws Exception {
    YamlConfiguration yaml = loadLanguageFile();
    yaml.set("messages." + GlossMessages.MENU_UNAVAILABLE.id(), "Custom {menu}");
    yaml.save(localization.languageFile());

    assertTrue(localization.selectLocale("pirate_SEA"));
    assertEquals("pirate_SEA", localization.activeLocale());
    assertEquals("Custom market", localization.text(
        GlossMessages.MENU_UNAVAILABLE,
        MessageArgs.builder().untrusted("menu", "market").build()
    ));
    assertEquals(GlossMessages.MENU_CLOSED.english(), localization.text(GlossMessages.MENU_CLOSED));
  }

  @Test
  public void rejectsStaleLanguageFileLocaleSelector() throws Exception {
    YamlConfiguration yaml = loadLanguageFile();
    yaml.set("locale", "fr_FR");
    yaml.save(localization.languageFile());

    assertFalse(localization.reload());
    assertEquals("en_US", localization.activeLocale());
    assertTrue(logRecords.stream().anyMatch(record -> record.getThrown() != null
        && record.getThrown().getMessage().contains("remove its top-level locale key")
        && record.getThrown().getMessage().contains("set language in plugins/Gloss/gloss.toml")
        && record.getThrown().getMessage().contains("/gloss reload")
        && record.getThrown().getMessage().contains("does not migrate the obsolete locale key")));
  }

  @Test
  public void rejectsInvalidReloadAndRetainsLastGoodSnapshot() throws Exception {
    YamlConfiguration yaml = loadLanguageFile();
    yaml.set("messages." + GlossMessages.PREVIEW_SCALE_SIZE.id(), "Taille {percent}%");
    yaml.save(localization.languageFile());
    assertTrue(localization.reload());

    MessageArgs arguments = MessageArgs.builder().untrusted("percent", 125).build();
    assertEquals("Taille 125%", localization.text(GlossMessages.PREVIEW_SCALE_SIZE, arguments));

    yaml.set("messages." + GlossMessages.PREVIEW_SCALE_SIZE.id(), "Argument absent");
    yaml.save(localization.languageFile());

    assertFalse(localization.reload());
    assertEquals("Taille 125%", localization.text(GlossMessages.PREVIEW_SCALE_SIZE, arguments));
  }

  @Test
  public void automaticReloadQueuesStableExactContentAndDoesNotRecreateDeletion() throws Exception {
    localization.close();
    AtomicLong clock = new AtomicLong();
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    localization = new GlossLocalization(
        temporaryFolder.newFolder("clocked-localization"), logger, VolmitLocales.ENGLISH, clock::get);
    YamlConfiguration yaml = loadLanguageFile();
    yaml.set("messages." + GlossMessages.MENU_UNAVAILABLE.id(), "Alpha {menu}");
    yaml.save(localization.languageFile());
    MessageArgs arguments = MessageArgs.builder().untrusted("menu", "market").build();

    clock.addAndGet(TimeUnit.SECONDS.toNanos(9L));
    localization.update();
    assertFalse("Alpha market".equals(localization.text(GlossMessages.MENU_UNAVAILABLE, arguments)));
    localization.update();
    assertEquals("Alpha market", localization.text(GlossMessages.MENU_UNAVAILABLE, arguments));

    FileTime appliedTime = Files.getLastModifiedTime(localization.languageFile().toPath());
    yaml.set("messages." + GlossMessages.MENU_UNAVAILABLE.id(), "Bravo {menu}");
    yaml.save(localization.languageFile());
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
    YamlConfiguration yaml = loadLanguageFile();
    yaml.set("messages.director.help.navigation.back", "&aRetour");
    yaml.save(localization.languageFile());
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

  private YamlConfiguration loadLanguageFile() throws Exception {
    File file = localization.languageFile();
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.load(file);
    return yaml;
  }

}
