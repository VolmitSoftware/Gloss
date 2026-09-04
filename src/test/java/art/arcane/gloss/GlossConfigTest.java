package art.arcane.gloss;

import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.ConfigDoc;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossConfigTest {
    @Test
    void dropShowAcceptsTomlBooleansAndExpressions() throws IOException {
        GlossConfigFile hidden = TomlCodec.fromToml("[drops]\nshow = false\n", GlossConfigFile.class);
        hidden.normalize();
        assertFalse(GlossConfig.from(hidden).drops().show().matches(null));

        GlossConfigFile dynamic = TomlCodec.fromToml("[drops]\nshow = \"world.time > 12000\"\n", GlossConfigFile.class);
        dynamic.normalize();
        GlossConfigFile restored = TomlCodec.fromToml(
            TomlCodec.toToml(dynamic, "gloss", ConfigExposePolicy.ALL), GlossConfigFile.class);
        restored.normalize();
        assertEquals("world.time > 12000", GlossConfig.from(restored).drops().show().expression());

        GlossConfigFile invalid = TomlCodec.fromToml("[drops]\nshow = 7\n", GlossConfigFile.class);
        assertThrows(IllegalArgumentException.class, invalid::normalize);
    }

    @Test
    void defaultsRoundTripThroughToml() throws IOException {
        GlossConfigFile defaults = new GlossConfigFile();
        defaults.normalize();
        String toml = TomlCodec.toToml(defaults, "gloss", ConfigExposePolicy.ALL);
        assertTrue(toml.contains("realDrops = true"));
        assertFalse(toml.contains("[realDrops"));
        GlossConfigFile parsed = TomlCodec.fromToml(toml, GlossConfigFile.class);
        parsed.normalize();

        assertEquals(GlossConfig.from(defaults), GlossConfig.from(parsed));

        GlossConfig snapshot = GlossConfig.from(parsed);
        assertEquals("en_US", snapshot.language());
        assertTrue(snapshot.splashScreen());
        assertTrue(snapshot.metrics());
        assertEquals(5, snapshot.hotload().watchIntervalTicks());
        assertEquals(0.26D, snapshot.holograms().stackDistance());
        assertEquals(2, snapshot.holograms().temporaryUpdateIntervalTicks());
        assertTrue(snapshot.holograms().interpolatedMotion());
        assertTrue(snapshot.holograms().highFrequencyAnimations());
        assertEquals(120, snapshot.holograms().maxAnimationFps());
        assertEquals(20000, snapshot.holograms().animationPacketBudget());
        assertEquals(40, snapshot.tablist().updateIntervalTicks());
        assertTrue(snapshot.groups().useVault());
        assertTrue(snapshot.bubbles().enabled());
        assertTrue(snapshot.bubbles().blacklistWorlds().isEmpty());
        assertTrue(snapshot.indicators().enabled());
        assertTrue(snapshot.commands().sounds());
        assertFalse(snapshot.motd().enabled());
        assertEquals(1.0F, snapshot.menus().uiScale());
        assertTrue(snapshot.previews().enabled());
        assertEquals(10.0D, snapshot.previews().lookDistance());
        assertEquals(0.65F, snapshot.previews().scale());
        assertEquals(GlossConfigFile.BUILDER_URL_DEFAULT, snapshot.editorSync().builderUrl());
        assertEquals(GlossConfigFile.EDITOR_SYNC_ENDPOINT_DEFAULT, snapshot.editorSync().endpoint());
        assertEquals("", snapshot.editorSync().createToken());
        assertEquals(60, snapshot.editorSync().sessionMinutes());
        assertFalse(snapshot.debug().hitbox());
        assertFalse(snapshot.debug().position());
        assertFalse(snapshot.debug().animator());
        assertTrue(snapshot.drops().preserveCustomNames());
        assertFalse(snapshot.drops().useItemDisplayNames());
        assertTrue(snapshot.drops().bundleVerticalLabels());
        assertEquals(GlossConfigFile.BUNDLE_HEADER_FORMAT_DEFAULT, snapshot.drops().bundleHeaderFormat());
        assertEquals(GlossConfigFile.BUNDLE_ENTRY_FORMAT_DEFAULT, snapshot.drops().bundleEntryFormat());
        assertEquals(GlossConfigFile.BUNDLE_MORE_FORMAT_DEFAULT, snapshot.drops().bundleMoreFormat());
        assertTrue(snapshot.realDrops().enabled());
        assertEquals(2, snapshot.realDrops().limits().updateIntervalTicks());
        assertEquals(20, snapshot.realDrops().limits().settledPollIntervalTicks());
        assertEquals(3, snapshot.realDrops().limits().maxVisualsPerStack());
        assertEquals(128, snapshot.realDrops().limits().maxVisualsPerChunk());
        assertTrue(snapshot.realDrops().motion().tumble());
        assertEquals(1.35F, snapshot.realDrops().motion().speedMultiplier());
        assertEquals("NATURAL", snapshot.realDrops().landing().mode());
        assertTrue(snapshot.realDrops().labels().enabled());
        assertEquals(List.of("BEDROCK", "BARRIER"), snapshot.realDrops().filters().materialBlacklist());
        assertTrue(snapshot.customItems().enabled());
        assertTrue(snapshot.customItems().providers().isEmpty());
    }

    @Test
    void missingItemDisplayNameSettingDecodesAsDisabled() throws IOException {
        GlossConfigFile parsed = TomlCodec.fromToml("""
            [drops]
            preserveCustomNames = true
            """, GlossConfigFile.class);
        parsed.normalize();

        assertFalse(parsed.drops.useItemDisplayNames);
        assertFalse(GlossConfig.from(parsed).drops().useItemDisplayNames());
    }

    @Test
    void customLocaleIdsArePreservedWhileBlankFallsBackToEnglish() {
        GlossConfigFile config = new GlossConfigFile();
        config.language = "  pirate_SEA  ";
        config.normalize();
        assertEquals("pirate_SEA", config.language);

        config.language = "   ";
        config.normalize();
        assertEquals("en_US", config.language);
    }

    @Test
    void outOfRangeValuesClampAndCanonicalizeBack(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(GlossConfigLoader.FILE_NAME);
        Files.writeString(file, """
            splashScreen = true

            [hotload]
            watchIntervalTicks = 0

            [holograms]
            temporaryUpdateIntervalTicks = 9999
            interpolatedMotion = false
            maxAnimationFps = 9999
            animationPacketBudget = 1

            [editor.sync]
            sessionMinutes = 1

            [preview]
            scale = 99.0

            [menus]
            uiScale = 0.01

            [items]
            customItemProviders = [" Oraxen ", "", "oraxen", "Nexo"]
            """);

        GlossConfigLoader loader = new GlossConfigLoader(tempDir.toFile());
        GlossConfigFile loaded = loader.loadForBoot();

        assertEquals(1, loaded.hotload.watchIntervalTicks);
        assertEquals(20, loaded.holograms.temporaryUpdateIntervalTicks);
        assertFalse(loaded.holograms.interpolatedMotion);
        assertEquals(240, loaded.holograms.maxAnimationFps);
        assertEquals(100, loaded.holograms.animationPacketBudget);
        assertEquals(5, loaded.editor.sync.sessionMinutes);
        assertEquals(4.0D, loaded.preview.scale);
        assertEquals(0.25D, loaded.menus.uiScale);
        assertEquals(List.of("oraxen", "nexo"), loaded.items.customItemProviders);

        String rewritten = Files.readString(file);
        assertTrue(rewritten.contains("watchIntervalTicks = 1"));
        assertTrue(rewritten.contains("temporaryUpdateIntervalTicks = 20"));
        assertTrue(rewritten.contains("interpolatedMotion = false"));
        assertTrue(rewritten.contains("maxAnimationFps = 240"));
        assertTrue(rewritten.contains("animationPacketBudget = 100"));
        assertTrue(rewritten.contains("sessionMinutes = 5"));
        assertTrue(rewritten.contains("scale = 4.0"));
        assertTrue(rewritten.contains("uiScale = 0.25"));
        assertTrue(rewritten.contains("customItemProviders = [\"oraxen\", \"nexo\"]"));
    }

    @Test
    void everyKnobIsEmittedWithACommentLine() {
        GlossConfigFile defaults = new GlossConfigFile();
        defaults.normalize();
        String toml = TomlCodec.toToml(defaults, "gloss", ConfigExposePolicy.ALL);
        List<String> lines = toml.lines().toList();

        Map<String, List<String>> leavesBySection = new LinkedHashMap<>();
        collectLeaves("", GlossConfigFile.class, leavesBySection);
        assertFalse(leavesBySection.isEmpty());

        for (Map.Entry<String, List<String>> section : leavesBySection.entrySet()) {
            int start = sectionStart(lines, section.getKey());
            int end = sectionEnd(lines, start);
            for (String leaf : section.getValue()) {
                int index = -1;
                for (int i = start; i < end; i++) {
                    if (lines.get(i).startsWith(leaf + " = ")) {
                        index = i;
                        break;
                    }
                }
                String where = section.getKey().isEmpty() ? leaf : section.getKey() + "." + leaf;
                assertTrue(index >= 0, "knob " + where + " is missing from the emitted toml");
                assertTrue(index > 0 && lines.get(index - 1).startsWith("#"),
                    "knob " + where + " is not preceded by a comment line");
            }
        }
    }

    @Test
    void damageIndicatorPresentationIsNotPartOfToml() {
        String toml = TomlCodec.toToml(new GlossConfigFile(), "gloss", ConfigExposePolicy.ALL);

        assertTrue(toml.contains("damageIndicators = true"));
        assertFalse(toml.contains("[damageIndicators]"));
        assertFalse(toml.contains("randomThrowForce"));
        assertFalse(toml.contains("damagePrefix"));
        assertFalse(toml.contains("showHeals"));
    }

    @Test
    void leadingControlsAreLanguageThenMetrics() {
        String toml = TomlCodec.toToml(new GlossConfigFile(), "gloss", ConfigExposePolicy.ALL);
        List<String> assignments = toml.lines()
            .filter(line -> !line.isBlank() && !line.startsWith("#") && !line.startsWith("["))
            .toList();

        assertTrue(assignments.get(0).startsWith("language = "));
        assertTrue(assignments.get(1).startsWith("metrics = "));
    }

    @Test
    void everySectionIsEmittedWithItsExplicitDescription() {
        String toml = TomlCodec.toToml(new GlossConfigFile(), "gloss", ConfigExposePolicy.ALL);
        List<String> lines = toml.lines().toList();
        Map<String, Field> sections = new LinkedHashMap<>();
        collectSections("", GlossConfigFile.class, sections);

        assertFalse(sections.isEmpty());
        for (Map.Entry<String, Field> section : sections.entrySet()) {
            ConfigDoc doc = section.getValue().getAnnotation(ConfigDoc.class);
            assertTrue(doc != null, "section " + section.getKey() + " has no explicit ConfigDoc");
            int header = lines.indexOf("[" + section.getKey() + "]");
            assertTrue(header > 0, "missing section header [" + section.getKey() + "]");
            assertEquals("# " + doc.value(), lines.get(header - 1));
        }
    }

    @Test
    void freshLoadWritesGlossTomlDirectlyInDataFolder(@TempDir Path tempDir) throws IOException {
        new GlossConfigLoader(tempDir.toFile()).loadForBoot();

        assertTrue(Files.isRegularFile(tempDir.resolve("gloss.toml")));
        assertFalse(Files.exists(tempDir.resolve("Gloss")));
    }

    @Test
    void unrelatedTomlFilesDoNotAffectCanonicalConfig(@TempDir Path tempDir) throws IOException {
        Path unrelated = tempDir.resolve("other.toml");
        Files.writeString(unrelated, "metrics = false\n");

        GlossConfigFile loaded = new GlossConfigLoader(tempDir.toFile()).loadForBoot();

        assertTrue(loaded.metrics);
        assertEquals("metrics = false\n", Files.readString(unrelated));
        assertTrue(Files.isRegularFile(tempDir.resolve("gloss.toml")));
    }

    @Test
    void brokenTomlKeepsLastGoodOnReload(@TempDir Path tempDir) throws IOException {
        GlossConfigLoader loader = new GlossConfigLoader(tempDir.toFile());
        loader.loadForBoot();
        Path file = tempDir.resolve(GlossConfigLoader.FILE_NAME);
        String canonical = Files.readString(file);

        String broken = "= = =\n[[[";
        Files.writeString(file, broken);
        assertThrows(IOException.class, loader::loadForReload);
        assertEquals(broken, Files.readString(file));

        GlossConfigFile recovered = loader.loadForBoot();
        GlossConfigFile defaults = new GlossConfigFile();
        defaults.normalize();
        assertEquals(GlossConfig.from(defaults), GlossConfig.from(recovered));
        assertEquals(canonical, Files.readString(file));
    }

    @Test
    void selfWriteSuppressionTracksCanonicalWrites(@TempDir Path tempDir) throws IOException {
        GlossConfigLoader loader = new GlossConfigLoader(tempDir.toFile());
        loader.loadForBoot();
        assertTrue(loader.isSelfWrite());

        Path file = tempDir.resolve(GlossConfigLoader.FILE_NAME);
        String edited = Files.readString(file).replace("splashScreen = true", "splashScreen = false");
        Files.writeString(file, edited);
        assertFalse(loader.isSelfWrite());

        GlossConfigFile reloaded = loader.loadForReload();
        assertFalse(reloaded.splashScreen);
        assertTrue(loader.isSelfWrite());
    }

    @Test
    void automaticReloadParsesTheCapturedBytesWithoutRereadingDisk(@TempDir Path tempDir) throws IOException {
        GlossConfigLoader loader = new GlossConfigLoader(tempDir.toFile());
        loader.loadForBoot();
        Path file = tempDir.resolve(GlossConfigLoader.FILE_NAME);
        String capturedContent = Files.readString(file).replace("splashScreen = true", "splashScreen = false");
        Files.writeString(file, capturedContent);
        GlossConfigLoader.ReloadSnapshot snapshot = loader.captureReloadSnapshot();

        Files.writeString(file, capturedContent.replace("metrics = true", "metrics = false"));
        GlossConfigFile reloaded = loader.loadForReload(snapshot);

        assertFalse(reloaded.splashScreen);
        assertTrue(reloaded.metrics);
        assertFalse(loader.isSelfWrite());
        assertTrue(Files.readString(file).contains("metrics = false"));
    }

    @Test
    void syncEndpointSanitizerFallsBackOnUnsafeValues() {
        assertEquals("https://relay.example.net/custom/v3",
            GlossConfigFile.sanitizeSyncEndpoint("HTTPS://relay.example.net/custom/v3/"));
        assertEquals("http://localhost:8080/v3",
            GlossConfigFile.sanitizeSyncEndpoint("http://localhost:8080/v3"));
        assertEquals("http://[::1]:8080/v3",
            GlossConfigFile.sanitizeSyncEndpoint("http://[::1]:8080/v3"));

        String fallback = GlossConfigFile.EDITOR_SYNC_ENDPOINT_DEFAULT;
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("http://relay.example.net/v3"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://trusted@evil.example/v3"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://relay.example/api"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://relay.example/v3?token=x"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://relay.example/v3#fragment"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint(" https://relay.example/v3"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint(
            "https://relay.example/" + "a".repeat(1100) + "/v3"));
    }

    @Test
    void syncCreateTokenSanitizerEnforcesCharsetAndLength() {
        assertEquals("a".repeat(22), GlossConfigFile.sanitizeSyncCreateToken("a".repeat(22)));
        assertEquals("A_b-9".repeat(20), GlossConfigFile.sanitizeSyncCreateToken("A_b-9".repeat(20)));
        assertEquals("", GlossConfigFile.sanitizeSyncCreateToken("short"));
        assertEquals("", GlossConfigFile.sanitizeSyncCreateToken("a".repeat(129)));
        assertEquals("", GlossConfigFile.sanitizeSyncCreateToken("a".repeat(21) + "/"));
        assertEquals("", GlossConfigFile.sanitizeSyncCreateToken(" " + "a".repeat(22)));
    }

    private static void collectLeaves(String path, Class<?> type, Map<String, List<String>> leavesBySection) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            Class<?> fieldType = field.getType();
            String childPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
            if (fieldType.getName().startsWith(GlossConfigFile.class.getName() + "$")) {
                collectLeaves(childPath, fieldType, leavesBySection);
            } else {
                assertTrue(field.isAnnotationPresent(ConfigDoc.class),
                    "knob " + childPath + " has no explicit ConfigDoc");
                leavesBySection.computeIfAbsent(path, ignored -> new ArrayList<>()).add(field.getName());
            }
        }
    }

    private static void collectSections(String path, Class<?> type, Map<String, Field> sections) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (!fieldType.getName().startsWith(GlossConfigFile.class.getName() + "$")) {
                continue;
            }
            String childPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
            sections.put(childPath, field);
            collectSections(childPath, fieldType, sections);
        }
    }

    private static int sectionStart(List<String> lines, String path) {
        if (path.isEmpty()) {
            return 0;
        }
        String header = "[" + path + "]";
        int index = lines.indexOf(header);
        assertTrue(index >= 0, "missing section header " + header);
        return index + 1;
    }

    private static int sectionEnd(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            if (lines.get(i).startsWith("[")) {
                return i;
            }
        }
        return lines.size();
    }
}
