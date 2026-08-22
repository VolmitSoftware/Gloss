package art.arcane.gloss.animation;

import art.arcane.gloss.doc.ShippedDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationLegacyDefaultUpgradeTest {
    private static final String LEGACY_NAMED_DEFAULT = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "mode": "ascend",
          "frameIntervalMs": 500,
          "frames": [
            "&cGloss",
            "&6Gloss",
            "&aGloss",
            "&bGloss"
          ]
        }
        """;
    private static final String LEGACY_STEPPED_DEFAULT = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "mode": "ascend",
          "frameIntervalMs": 500,
          "frames": [
            "&c",
            "&6",
            "&a",
            "&b"
          ]
        }
        """;

    @TempDir
    File folder;

    @Test
    void upgradesTheExactLegacyNamedShippedRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        Files.writeString(file.toPath(), LEGACY_NAMED_DEFAULT, StandardCharsets.UTF_8);

        assertTrue(AnimationService.upgradeLegacyRainbowDefault(defaults()));

        assertSmoothRainbow(file);
    }

    @Test
    void upgradesTheExactLegacySteppedShippedRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        Files.writeString(file.toPath(), LEGACY_STEPPED_DEFAULT, StandardCharsets.UTF_8);

        assertTrue(AnimationService.upgradeLegacyRainbowDefault(defaults()));

        assertSmoothRainbow(file);
    }

    @Test
    void upgradesTheExactPhaseLockedSmoothRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        Files.writeString(file.toPath(), phaseLockedSmoothRainbow(), StandardCharsets.UTF_8);

        assertTrue(AnimationService.upgradeLegacyRainbowDefault(defaults()));

        assertSmoothRainbow(file);
    }

    @Test
    void preservesAnEditedPhaseLockedSmoothRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        String edited = phaseLockedSmoothRainbow().replace("[FF0019]", "[FFFFFF]");
        Files.writeString(file.toPath(), edited, StandardCharsets.UTF_8);

        assertFalse(AnimationService.upgradeLegacyRainbowDefault(defaults()));
        assertEquals(edited, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void preservesAnyUserEditedRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        String edited = LEGACY_NAMED_DEFAULT.replace("&bGloss", "&bCustom");
        Files.writeString(file.toPath(), edited, StandardCharsets.UTF_8);

        assertFalse(AnimationService.upgradeLegacyRainbowDefault(defaults()));
        assertEquals(edited, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void preservesFormattingVariantsOfTheLegacyRainbow() throws IOException {
        File file = new File(folder, "rainbow.json");
        String reformatted = LEGACY_NAMED_DEFAULT.replace("  \"revision\"", "    \"revision\"");
        Files.writeString(file.toPath(), reformatted, StandardCharsets.UTF_8);

        assertFalse(AnimationService.upgradeLegacyRainbowDefault(defaults()));
        assertEquals(reformatted, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    private void assertSmoothRainbow(File file) throws IOException {
        String upgraded = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        AnimationDoc doc = AnimationDoc.parse(file.getName(), upgraded);
        assertEquals(53, doc.frameIntervalMs());
        assertEquals(60, doc.frames().size());
        assertEquals("[FF0000]", doc.frames().getFirst());
        assertEquals("[FF0019]", doc.frames().getLast());
        assertEquals(60, doc.frames().stream().distinct().count());
        AnimationClip clip = new AnimationClip("rainbow", 1000.0D / doc.frameIntervalMs(),
            doc.toMode(), doc.frames());
        long cycleMs = doc.frameIntervalMs() * doc.frames().size();
        for (long startMs = 0L; startMs < cycleMs; startMs++) {
            assertNotEquals(clip.frameAt(startMs), clip.frameAt(startMs + 3000L));
        }
        for (int index = 0; index < doc.frames().size(); index++) {
            int current = frameColor(doc.frames().get(index));
            int next = frameColor(doc.frames().get((index + 1) % doc.frames().size()));
            for (int shift : List.of(16, 8, 0)) {
                int delta = Math.abs(((current >> shift) & 0xFF) - ((next >> shift) & 0xFF));
                assertTrue(delta <= 26);
            }
        }
        assertFalse((doc.frames().getFirst() + "&lONLINE").contains("GlossONLINE"));
    }

    private int frameColor(String frame) {
        return Integer.parseInt(frame.substring(1, 7), 16);
    }

    private String phaseLockedSmoothRainbow() throws IOException {
        try (InputStream stream = AnimationLegacyDefaultUpgradeTest.class.getResourceAsStream(
            "/legacy-defaults/animations/rainbow-50ms.json")) {
            if (stream == null) {
                throw new IOException("Missing phase-locked rainbow test resource");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ShippedDefaults defaults() {
        return new ShippedDefaults(AnimationDoc.KIND, folder, List.of("rainbow"));
    }
}
