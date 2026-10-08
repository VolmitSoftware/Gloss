package art.arcane.gloss.forge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import art.arcane.gloss.GlossConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackBuilderTest {
    private static final Path FIXTURE_IMAGES = Path.of("src/test/resources/forge/images");
    private static final String BRAND = """
        {"schemaVersion":1,"revision":1,"namespace":"gloss","font":"glyphs","glyphs":[
          {"id":"coin","image":"icons/coin.png","height":8,"ascent":7,"fallback":"$"},
          {"id":"bar","image":"hud/bar.png","height":8,"ascent":7,"frames":4}],
         "space":{"enabled":true,"range":[-2,2]}}
        """;

    @TempDir
    Path folder;

    private GlyphRegistry registry(String raw) {
        GlyphLedger ledger = GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE);
        return GlyphRegistry.build(Map.of("brand", GlyphDoc.parse("brand.json", raw)), ledger,
            new PackBuilder(FIXTURE_IMAGES).probe());
    }

    @Test
    void modernPackMetadataAcceptsTheCurrentMinorVersion() throws Exception {
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES).build(registry(BRAND), folder.resolve("modern"), 97);
        String metadata = Files.readString(artifact.directory().resolve("pack.mcmeta"));
        assertTrue(metadata.contains("\"min_format\": 97"), metadata);
        assertTrue(metadata.contains("\"max_format\": 97"), metadata);
        assertFalse(metadata.contains("\"pack_format\""), metadata);
    }

    @Test
    void writesTheDirectoryZipAndHash() throws IOException {
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES).build(registry(BRAND), folder.resolve("out"), 64);

        assertTrue(Files.isDirectory(artifact.directory()));
        assertTrue(Files.isRegularFile(artifact.zip()));
        assertTrue(Files.isRegularFile(artifact.directory().resolve("pack.mcmeta")));
        assertTrue(Files.isRegularFile(artifact.directory()
            .resolve("assets/gloss/textures/font/coin.png")));
        assertEquals(40, artifact.sha1Hex().length());
        assertEquals(artifact.sha1Hex(),
            Files.readString(folder.resolve("out/gloss-pack.sha1"), StandardCharsets.UTF_8).trim());
        assertEquals(20, artifact.sha1().length);
    }

    @Test
    void packMcmetaCarriesTheRequestedFormat() throws IOException {
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES).build(registry(BRAND), folder.resolve("out"), 64);

        String mcmeta = Files.readString(artifact.directory().resolve("pack.mcmeta"), StandardCharsets.UTF_8);
        assertTrue(mcmeta.contains("\"pack_format\": 64"), mcmeta);
        assertTrue(mcmeta.contains("Gloss glyphs"), mcmeta);
    }

    @Test
    void fontJsonCarriesOneBitmapProviderPerGlyphAndOneSpaceProvider() throws IOException {
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES).build(registry(BRAND), folder.resolve("out"), 64);

        String font = Files.readString(artifact.directory().resolve("assets/gloss/font/glyphs.json"),
            StandardCharsets.UTF_8);
        assertTrue(font.contains("\"file\": \"gloss:font/coin.png\""), font);
        assertTrue(font.contains("\"file\": \"gloss:font/bar.png\""), font);
        assertTrue(font.contains("\"height\": 8"), font);
        assertTrue(font.contains("\"ascent\": 7"), font);
        assertTrue(font.contains("\"type\": \"space\""), font);
        assertTrue(font.contains("\\uE0"), "codepoints must be escaped, not raw: " + font);
        assertFalse(font.chars().anyMatch(c -> c > 0x7E), "the font file must stay ascii: " + font);
    }

    @Test
    void rebuildingTheSameInputsProducesIdenticalBytes() throws IOException {
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES);
        GlyphRegistry registry = registry(BRAND);

        PackArtifact first = builder.build(registry, folder.resolve("a"), 64);
        PackArtifact second = builder.build(registry, folder.resolve("b"), 64);

        assertArrayEquals(Files.readAllBytes(first.zip()), Files.readAllBytes(second.zip()));
        assertEquals(first.sha1Hex(), second.sha1Hex());
    }

    @Test
    void rebuildingOverAnExistingOutputDirectoryStaysIdentical() throws IOException {
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES);
        GlyphRegistry registry = registry(BRAND);
        Path out = folder.resolve("out");

        PackArtifact first = builder.build(registry, out, 64);
        byte[] firstBytes = Files.readAllBytes(first.zip());
        Files.writeString(first.directory().resolve("assets/gloss/font/stale.json"), "{}");
        PackArtifact second = builder.build(registry, out, 64);

        assertArrayEquals(firstBytes, Files.readAllBytes(second.zip()));
        assertFalse(Files.exists(second.directory().resolve("assets/gloss/font/stale.json")));
    }

    @Test
    void zipEntriesAreStoredSortedAndTimestampFree() throws IOException {
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES).build(registry(BRAND), folder.resolve("out"), 64);

        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(artifact.zip().toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                names.add(entry.getName());
                assertEquals(ZipEntry.STORED, entry.getMethod(), entry.getName());
                assertTrue(entry.getTime() <= 0L, entry.getName() + " carries a build timestamp");
            }
        }

        assertEquals(names.stream().sorted().toList(), names);
        assertTrue(names.contains("pack.mcmeta"));
        assertTrue(names.contains("assets/gloss/font/glyphs.json"));
        assertTrue(names.contains("assets/gloss/textures/font/coin.png"));
    }

    @Test
    void refusesAnImageTallerThanTheClientAllows() {
        String doc = """
            {"schemaVersion":1,"revision":1,"glyphs":[
              {"id":"huge","image":"big/huge.png","height":8}],"space":{"enabled":false}}
            """;
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES);
        GlyphRegistry registry = registry(doc);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> builder.build(registry, folder.resolve("out"), 64));

        assertTrue(failure.getMessage().contains("big/huge.png"), failure.getMessage());
    }

    @Test
    void refusesASheetWhoseWidthIsNotAMultipleOfItsFrameCount() {
        String doc = """
            {"schemaVersion":1,"revision":1,"glyphs":[
              {"id":"coin","image":"icons/coin.png","height":8,"frames":3}],"space":{"enabled":false}}
            """;
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES);
        GlyphRegistry registry = registry(doc);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> builder.build(registry, folder.resolve("out"), 64));

        assertTrue(failure.getMessage().contains("icons/coin.png"), failure.getMessage());
    }

    @Test
    void refusesAMissingImageByName() {
        String doc = """
            {"schemaVersion":1,"revision":1,"glyphs":[
              {"id":"gone","image":"icons/gone.png","height":8}],"space":{"enabled":false}}
            """;
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES);
        GlyphRegistry registry = registry(doc);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> builder.build(registry, folder.resolve("out"), 64));

        assertTrue(failure.getMessage().contains("icons/gone.png"), failure.getMessage());
    }

    @Test
    void multipleFontsKeepTheirOwnTexturesAndFontProviders() throws IOException {
        GlyphDoc first = GlyphDoc.parse("a.json", BRAND);
        GlyphDoc second = GlyphDoc.parse("b.json", """
            {"schemaVersion":1,"revision":1,"namespace":"hud","font":"labels","glyphs":[
              {"id":"badge","image":"icons/coin.png","height":8}],"space":{"enabled":false}}
            """);
        GlyphRegistry registry = GlyphRegistry.build(Map.of("a", first, "b", second),
            GlyphLedger.load(folder.resolve("ledger.json"), GlyphLedger.DEFAULT_BASE),
            new PackBuilder(FIXTURE_IMAGES).probe());
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES).build(registry, folder.resolve("out"), 64);
        String font = Files.readString(artifact.directory().resolve("assets/hud/font/labels.json"));
        assertTrue(font.contains("hud:font/badge.png"));
        assertFalse(font.contains("coin.png"));
        assertEquals("hud", registry.glyph("badge").orElseThrow().namespace());
        assertTrue(Files.isRegularFile(artifact.directory().resolve("assets/hud/textures/font/badge.png")));
    }

    @Test
    void waypointAssetsUseTheNativeStyleAndGuiSpritePaths() throws IOException {
        GlyphRegistry registry = registry("""
            {"schemaVersion":1,"revision":1,"namespace":"trails","waypointStyles":[
              {"id":"quest","nearDistance":12,"farDistance":80,"sprites":[
                {"id":"quest-near","image":"icons/coin.png"},
                {"id":"quest-far","image":"hud/bar.png"}]}]}
            """);
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES).build(registry, folder.resolve("waypoints"), 97);
        String style = Files.readString(artifact.directory().resolve("assets/trails/waypoint_style/quest.json"));
        assertTrue(style.contains("\"near_distance\": 12.0"), style);
        assertTrue(style.contains("\"far_distance\": 80.0"), style);
        assertTrue(style.contains("trails:quest-near"), style);
        assertTrue(style.contains("trails:quest-far"), style);
        assertArrayEquals(Files.readAllBytes(FIXTURE_IMAGES.resolve("icons/coin.png")),
            Files.readAllBytes(artifact.directory().resolve("assets/trails/textures/gui/sprites/hud/locator_bar_dot/quest-near.png")));
        assertEquals(1, registry.waypointStyles().size());
        assertTrue(registry.waypointStyles().containsKey("trails:quest"));
    }

    @Test
    void conflictingWaypointSpritesAreRejectedBeforePublishing() {
        assertThrows(IllegalArgumentException.class, () -> registry("""
            {"schemaVersion":1,"revision":1,"waypointStyles":[
              {"id":"first","sprites":[{"id":"dot","image":"icons/coin.png"}]},
              {"id":"second","sprites":[{"id":"dot","image":"hud/bar.png"}]}]}
            """));
        assertThrows(IllegalArgumentException.class, () -> registry("""
            {"schemaVersion":1,"revision":1,"waypointStyles":[
              {"id":"first","nearDistance":50,"farDistance":10,"sprites":[{"id":"dot","image":"icons/coin.png"}]}]}
            """));
    }

    @Test
    void refusedNewInputPreservesThePublishedPackBytes() throws IOException {
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES);
        Path output = folder.resolve("out");
        PackArtifact first = builder.build(registry(BRAND), output, 64);
        byte[] previous = Files.readAllBytes(first.zip());
        GlyphRegistry bad = registry("""
            {"schemaVersion":1,"revision":1,"glyphs":[
              {"id":"missing","image":"missing.png","height":8}],"space":{"enabled":false}}
            """);
        assertThrows(IllegalArgumentException.class, () -> builder.build(bad, output, 64));
        assertArrayEquals(previous, Files.readAllBytes(first.zip()));
        assertArrayEquals(previous, Files.readAllBytes(output.resolve(PackBuilder.ZIP_NAME)));
    }

    @Test
    void previousArtifactBytesStayImmutableAfterAChangedBuild() throws IOException {
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES);
        Path output = folder.resolve("out");
        PackArtifact first = builder.build(registry(BRAND), output, 64);
        byte[] original = Files.readAllBytes(first.zip());
        PackArtifact next = builder.build(registry(BRAND), output, 97);
        assertFalse(first.zip().equals(next.zip()));
        assertArrayEquals(original, Files.readAllBytes(first.zip()));
    }

    @Test
    void configuredSourceLimitRejectsTexturesBeforePublication() {
        GlossConfig.Images limits = new GlossConfig.Images(8, 256, 16, 16, 1048576, 8, 8, 1);
        PackBuilder builder = new PackBuilder(FIXTURE_IMAGES, () -> limits);
        assertThrows(IllegalArgumentException.class, () -> builder.build(registry(BRAND), folder.resolve("out"), 64));
        assertFalse(Files.exists(folder.resolve("out/pack")));
    }

    @Test
    void anEmptyRegistryStillProducesALoadablePack() throws IOException {
        PackArtifact artifact = new PackBuilder(FIXTURE_IMAGES)
            .build(GlyphRegistry.EMPTY, folder.resolve("out"), 64);

        assertTrue(Files.isRegularFile(artifact.directory().resolve("pack.mcmeta")));
        assertTrue(Files.isRegularFile(artifact.zip()));
    }

    @Test
    void aggregateFileByteAndPixelRefusalsPreserveTheLastPublishedPack() throws IOException {
        List<GlossConfig.PackLimits> refusals = List.of(
            new GlossConfig.PackLimits(2, 1000000, 1000000, 8, 10000000, 600),
            new GlossConfig.PackLimits(100, 1, 1000000, 8, 10000000, 600),
            new GlossConfig.PackLimits(100, 1000000, 1, 8, 10000000, 600));
        for (int index = 0; index < refusals.size(); index++) {
            AtomicReference<GlossConfig.PackLimits> limits = new AtomicReference<>(GlossConfig.PackLimits.DEFAULT);
            PackBuilder builder = new PackBuilder(new PackBuilder.Options(FIXTURE_IMAGES,
                () -> GlossConfig.current().images(), limits::get, new PackArtifactStore()));
            Path output = folder.resolve("limited-" + index);
            PackArtifact first = builder.build(registry(BRAND), output, 64);
            byte[] original = Files.readAllBytes(first.zip());
            limits.set(refusals.get(index));

            assertThrows(IOException.class, () -> builder.build(registry(BRAND), output, 97));

            assertArrayEquals(original, Files.readAllBytes(first.zip()));
            assertArrayEquals(original, Files.readAllBytes(output.resolve(PackBuilder.ZIP_NAME)));
            assertEquals(first.sha1Hex(), Files.readString(output.resolve(PackBuilder.SHA1_NAME)).strip());
            try (Stream<Path> paths = Files.list(output)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".build-")));
            }
        }
    }

    @Test
    void zipDirectoryOverheadAlsoCountsAgainstTheBuildByteLimit() throws IOException {
        AtomicReference<GlossConfig.PackLimits> limits = new AtomicReference<>(GlossConfig.PackLimits.DEFAULT);
        PackBuilder builder = new PackBuilder(new PackBuilder.Options(FIXTURE_IMAGES,
            () -> GlossConfig.current().images(), limits::get, new PackArtifactStore()));
        Path output = folder.resolve("zip-limit");
        PackArtifact first = builder.build(registry(BRAND), output, 64);
        long payload = 0;
        try (ZipFile zip = new ZipFile(first.zip().toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                payload += entry.getSize();
            }
        }
        assertTrue(Files.size(first.zip()) > payload);
        limits.set(new GlossConfig.PackLimits(100, payload, 1000000, 8, 10000000, 600));

        IOException failure = assertThrows(IOException.class, () -> builder.build(registry(BRAND), output, 64));

        assertTrue(failure.getMessage().contains("ZIP"));
        assertEquals(first.sha1Hex(), Files.readString(output.resolve(PackBuilder.SHA1_NAME)).strip());
    }

    @Test
    void aFullProtectedArtifactBudgetRefusesPublicationWithoutRemovingTheCurrentPack() throws IOException {
        PackArtifactStore artifacts = new PackArtifactStore();
        GlossConfig.PackLimits limits = new GlossConfig.PackLimits(100, 1000000, 1000000, 2, 10000000, 600);
        PackBuilder builder = new PackBuilder(new PackBuilder.Options(FIXTURE_IMAGES,
            () -> GlossConfig.current().images(), () -> limits, artifacts));
        Path output = folder.resolve("retention-limit");
        PackArtifact first = builder.build(registry(BRAND), output, 64);
        PackArtifact second = builder.build(registry(BRAND), output, 97);

        assertThrows(IOException.class, () -> builder.build(registry(BRAND), output, 98));

        assertTrue(Files.exists(first.zip()));
        assertArrayEquals(Files.readAllBytes(second.zip()), Files.readAllBytes(output.resolve(PackBuilder.ZIP_NAME)));
        assertEquals(second.sha1Hex(), Files.readString(output.resolve(PackBuilder.SHA1_NAME)).strip());
    }
}
