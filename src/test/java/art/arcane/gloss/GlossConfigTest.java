package art.arcane.gloss;

import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
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
    void defaultsRoundTripThroughToml() throws IOException {
        GlossConfigFile defaults = new GlossConfigFile();
        defaults.normalize();
        String toml = TomlCodec.toToml(defaults, "gloss", ConfigExposePolicy.ALL);
        GlossConfigFile parsed = TomlCodec.fromToml(toml, GlossConfigFile.class);
        parsed.normalize();

        assertEquals(GlossConfig.from(defaults), GlossConfig.from(parsed));

        GlossConfig snapshot = GlossConfig.from(parsed);
        assertTrue(snapshot.splashScreen());
        assertTrue(snapshot.metrics());
        assertEquals(5, snapshot.hotload().watchIntervalTicks());
        assertEquals(0.26D, snapshot.holograms().stackDistance());
        assertEquals(2, snapshot.holograms().temporaryUpdateIntervalTicks());
        assertTrue(snapshot.holograms().interpolatedMotion());
        assertEquals(48, snapshot.holograms().textArtMaxWidth());
        assertTrue(snapshot.holograms().highFrequencyAnimations());
        assertEquals(120, snapshot.holograms().maxAnimationFps());
        assertEquals(20000, snapshot.holograms().animationPacketBudget());
        assertEquals(40, snapshot.tablist().updateIntervalTicks());
        assertTrue(snapshot.groups().useVault());
        assertTrue(snapshot.bubbles().enabled());
        assertTrue(snapshot.bubbles().blacklistWorlds().isEmpty());
        assertTrue(snapshot.indicators().showHeals());
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
        assertTrue(snapshot.drops().useItemDisplayNames());
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
        assertEquals("NATURAL", snapshot.realDrops().landing().mode());
        assertTrue(snapshot.realDrops().labels().enabled());
        assertEquals(List.of("BEDROCK", "BARRIER"), snapshot.realDrops().filters().materialBlacklist());
        assertTrue(snapshot.customItems().enabled());
        assertTrue(snapshot.customItems().providers().isEmpty());
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
            textArtMaxWidth = 1
            maxAnimationFps = 9999
            animationPacketBudget = 1

            [editor.sync]
            sessionMinutes = 1

            [preview]
            scale = 99.0

            [menus]
            uiScale = 0.01

            [realDrops.limits]
            updateIntervalTicks = 0
            settledPollIntervalTicks = 999
            maxVisualsPerStack = 99
            maxVisualsPerChunk = 1
            viewRange = 999.0
            spread = -2.0

            [realDrops.motion]
            degreesPerSecondX = -9999.0
            degreesPerSecondY = 9999.0
            degreesPerSecondZ = 9999.0
            variance = 8.0

            [realDrops.landing]
            mode = "sideways"
            tiltDegrees = 99.0
            transitionTicks = 99

            [realDrops.labels]
            yOffset = -1.0
            scale = 99.0
            viewRange = 1.0
            billboard = "diagonal"
            backgroundRed = -1
            backgroundGreen = 999
            backgroundBlue = 999
            backgroundAlpha = -1

            [items]
            customItemProviders = [" Oraxen ", "", "oraxen", "Nexo"]
            """);

        GlossConfigLoader loader = new GlossConfigLoader(tempDir.toFile());
        GlossConfigFile loaded = loader.loadForBoot();

        assertEquals(1, loaded.hotload.watchIntervalTicks);
        assertEquals(20, loaded.holograms.temporaryUpdateIntervalTicks);
        assertFalse(loaded.holograms.interpolatedMotion);
        assertEquals(8, loaded.holograms.textArtMaxWidth);
        assertEquals(240, loaded.holograms.maxAnimationFps);
        assertEquals(100, loaded.holograms.animationPacketBudget);
        assertEquals(5, loaded.editor.sync.sessionMinutes);
        assertEquals(4.0D, loaded.preview.scale);
        assertEquals(0.25D, loaded.menus.uiScale);
        assertEquals(1, loaded.realDrops.limits.updateIntervalTicks);
        assertEquals(200, loaded.realDrops.limits.settledPollIntervalTicks);
        assertEquals(5, loaded.realDrops.limits.maxVisualsPerStack);
        assertEquals(8, loaded.realDrops.limits.maxVisualsPerChunk);
        assertEquals(128.0D, loaded.realDrops.limits.viewRange);
        assertEquals(0.0D, loaded.realDrops.limits.spread);
        assertEquals(-1440.0D, loaded.realDrops.motion.degreesPerSecondX);
        assertEquals(1440.0D, loaded.realDrops.motion.degreesPerSecondY);
        assertEquals(1440.0D, loaded.realDrops.motion.degreesPerSecondZ);
        assertEquals(1.0D, loaded.realDrops.motion.variance);
        assertEquals("NATURAL", loaded.realDrops.landing.mode);
        assertEquals(45.0D, loaded.realDrops.landing.tiltDegrees);
        assertEquals(20, loaded.realDrops.landing.transitionTicks);
        assertEquals(0.0D, loaded.realDrops.labels.yOffset);
        assertEquals(4.0D, loaded.realDrops.labels.scale);
        assertEquals(4.0D, loaded.realDrops.labels.viewRange);
        assertEquals("CENTER", loaded.realDrops.labels.billboard);
        assertEquals(0, loaded.realDrops.labels.backgroundRed);
        assertEquals(255, loaded.realDrops.labels.backgroundGreen);
        assertEquals(255, loaded.realDrops.labels.backgroundBlue);
        assertEquals(0, loaded.realDrops.labels.backgroundAlpha);
        assertEquals(List.of("oraxen", "nexo"), loaded.items.customItemProviders);

        String rewritten = Files.readString(file);
        assertTrue(rewritten.contains("watchIntervalTicks = 1"));
        assertTrue(rewritten.contains("temporaryUpdateIntervalTicks = 20"));
        assertTrue(rewritten.contains("interpolatedMotion = false"));
        assertTrue(rewritten.contains("textArtMaxWidth = 8"));
        assertTrue(rewritten.contains("maxAnimationFps = 240"));
        assertTrue(rewritten.contains("animationPacketBudget = 100"));
        assertTrue(rewritten.contains("sessionMinutes = 5"));
        assertTrue(rewritten.contains("scale = 4.0"));
        assertTrue(rewritten.contains("uiScale = 0.25"));
        assertTrue(rewritten.contains("maxVisualsPerChunk = 8"));
        assertTrue(rewritten.contains("degreesPerSecondX = -1440.0"));
        assertTrue(rewritten.contains("billboard = \"CENTER\""));
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
    void syncEndpointSanitizerFallsBackOnUnsafeValues() {
        assertEquals("https://relay.example.net/custom/v2",
            GlossConfigFile.sanitizeSyncEndpoint("HTTPS://relay.example.net/custom/v2/"));
        assertEquals("http://localhost:8080/v2",
            GlossConfigFile.sanitizeSyncEndpoint("http://localhost:8080/v2"));
        assertEquals("http://[::1]:8080/v2",
            GlossConfigFile.sanitizeSyncEndpoint("http://[::1]:8080/v2"));

        String fallback = GlossConfigFile.EDITOR_SYNC_ENDPOINT_DEFAULT;
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("http://relay.example.net/v2"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://trusted@evil.example/v2"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://relay.example/v1"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://relay.example/v2?token=x"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint("https://relay.example/v2#fragment"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint(" https://relay.example/v2"));
        assertEquals(fallback, GlossConfigFile.sanitizeSyncEndpoint(
            "https://relay.example/" + "a".repeat(1100) + "/v2"));
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
                leavesBySection.computeIfAbsent(path, ignored -> new ArrayList<>()).add(field.getName());
            }
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
