package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.emoji.EmojiService;
import art.arcane.gloss.forge.GlyphService;
import art.arcane.gloss.forge.PackArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphServiceLifecycleTest {
    @TempDir File folder;

    @Test
    void disabledGenerationCannotPublishAndNextEnableBuildsAgain() throws Exception {
        Gloss previous = Gloss.instance;
        try (CharacterizationHarness harness = new CharacterizationHarness(folder)) {
            Gloss.instance = harness.gloss;
            harness.configure(config -> config.features.forge = true);
            install(harness.gloss, "watchdog", new DataWatchdog(harness.gloss));
            install(harness.gloss, "emoji", new EmojiService(harness.gloss));
            harness.deferImmediateTasks = true;
            GlyphService service = new GlyphService(harness.gloss);
            try {
                service.enable();
                CompletableFuture<Boolean> first = service.build();
                assertSame(first, service.build());
                CharacterizationHarness.awaitTrue("pack publication queued", () -> !harness.immediateTasks.isEmpty(), 5000);
                assertFalse(first.isDone());
                service.disable();
                harness.drainImmediate();
                assertFalse(first.get(5, TimeUnit.SECONDS));
                assertTrue(service.artifact().isEmpty());

                service.enable();
                CompletableFuture<Boolean> second = service.build();
                CharacterizationHarness.awaitTrue("next pack publication queued", () -> !harness.immediateTasks.isEmpty(), 5000);
                harness.drainImmediate();
                assertTrue(second.get(5, TimeUnit.SECONDS));
                assertTrue(service.artifact().isPresent());
            } finally {
                service.disable();
                stopWorker(service);
            }
        } finally {
            Gloss.instance = previous;
        }
    }

    @Test
    void rejectedOwnerSchedulerCompletesBuildFailureWithoutPublishing() throws Exception {
        Gloss previous = Gloss.instance;
        try (CharacterizationHarness harness = new CharacterizationHarness(folder)) {
            Gloss.instance = harness.gloss;
            harness.configure(config -> config.features.forge = true);
            install(harness.gloss, "watchdog", new DataWatchdog(harness.gloss));
            install(harness.gloss, "emoji", new EmojiService(harness.gloss));
            harness.enabled(false);
            GlyphService service = new GlyphService(harness.gloss);
            try {
                service.enable();
                assertFalse(service.build().get(5, TimeUnit.SECONDS));
                assertTrue(service.artifact().isEmpty());
            } finally {
                service.disable();
                stopWorker(service);
            }
        } finally {
            Gloss.instance = previous;
        }
    }

    @Test
    void aBuildLimitRefusalDuringReloadKeepsThePreviousRuntimePack() throws Exception {
        Gloss previous = Gloss.instance;
        try (CharacterizationHarness harness = new CharacterizationHarness(folder)) {
            Gloss.instance = harness.gloss;
            harness.configure(config -> config.features.forge = true);
            install(harness.gloss, "watchdog", new DataWatchdog(harness.gloss));
            install(harness.gloss, "emoji", new EmojiService(harness.gloss));
            harness.deferImmediateTasks = true;
            GlyphService service = new GlyphService(harness.gloss);
            try {
                service.enable();
                CompletableFuture<Boolean> first = service.build();
                CharacterizationHarness.awaitTrue("initial pack publication queued",
                    () -> !harness.immediateTasks.isEmpty(), 5000);
                harness.drainImmediate();
                assertTrue(first.get(5, TimeUnit.SECONDS));
                PackArtifact original = service.artifact().orElseThrow();
                Path images = folder.toPath().resolve("images");
                Files.createDirectories(images);
                Files.copy(Path.of("src/test/resources/forge/images/icons/coin.png"), images.resolve("coin.png"));
                Files.writeString(folder.toPath().resolve("glyphs/limited.json"), """
                    {"schemaVersion":1,"revision":1,"glyphs":[
                      {"id":"coin","image":"coin.png","height":8}],"space":{"enabled":false}}
                    """);
                harness.configure(config -> {
                    config.features.forge = true;
                    config.forge.maxBuildFiles = 2;
                });

                service.reload();

                assertFalse(service.build().get(5, TimeUnit.SECONDS));
                assertSame(original, service.artifact().orElseThrow());
                assertTrue(Files.isRegularFile(original.zip()));
            } finally {
                service.disable();
                stopWorker(service);
            }
        } finally {
            Gloss.instance = previous;
        }
    }

    private static void install(Gloss plugin, String name, Object value) throws Exception {
        Field field = Gloss.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private static void stopWorker(GlyphService service) throws Exception {
        Field field = GlyphService.class.getDeclaredField("worker");
        field.setAccessible(true);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(service);
        if (worker != null) {
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
