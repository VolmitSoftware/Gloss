package art.arcane.gloss.hologram;

import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.api.ParticleTextSpan;
import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.entity.EntityOverlayText;
import art.arcane.gloss.particle.ParticleService;
import art.arcane.gloss.particle.ParticleText;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryHologramParticleTest {
    @TempDir
    File dataFolder;

    @Test
    void standaloneLabelTargetUsesRenderedRichTextBounds() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            EntityOverlayDoc settings = EntityOverlayDoc.parse("default.json", """
                {"schemaVersion":2,"revision":1,"particleLayers":[{
                  "id":"label","target":{"scope":"label"},
                  "geometry":{"type":"outline","spacing":0.2},
                  "particle":{"key":"minecraft:dust","color":"#ff9900","size":0.5},
                  "emission":{"intervalTicks":1,"pattern":"steady"}}]}
                """);
            TemporaryHologramDisplay temporary = harness.temporary("drop-label",
                harness.at(world, 0D, 65D, 2D), Long.MAX_VALUE);
            temporary.setLines(List.of("<red>Label</red>"));
            temporary.setParticleLayers(settings.particleLayers());
            cacheParticle(harness.gloss.particles(), settings.particleLayers().getFirst().particle());
            temporary.drive(true);
            harness.driveTemporaryParticles();

            assertFalse(viewer.particleLocations.isEmpty());
            assertTrue(viewer.particleLocations.stream().allMatch(location -> Math.abs(location.getX()) < 0.6D));
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
        }
    }

    @Test
    void formattedAnimatedOverlayEmitsItsNamedSpanThroughTheParticleService() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 192, 0);
            EntityOverlayDoc settings = EntityOverlayDoc.parse("default.json", """
                {"schemaVersion":2,"revision":1,
                  "style":{"scaleX":0.8,"scaleY":0.9,"scaleZ":1},
                  "lines":[{"id":"stats","text":"ATK ARM"},
                    {"id":"custom","text":"<gold><particles:probe>Engine {{ entity.health + 1 }} {{ player.name }}</particles></gold>"},
                    {"id":"animation","text":"|animation.fast|"}],
                  "particleLayers":[{"id":"probe","target":{"scope":"span","name":"probe"},
                    "geometry":{"type":"outline","spacing":0.2},
                    "particle":{"key":"minecraft:dust","color":"#ff9900","size":0.5},
                    "emission":{"intervalTicks":1,"pattern":"steady"}}]}
                """);
            EntityOverlayText.Prepared prepared = EntityOverlayText.prepare(harness.gloss, viewer.proxy,
                settings, new EntityOverlayText.Snapshot("Cow", 20, 20, 20, 0, 3, 0, 1, "cow", 5), List.of());
            ParticleText.Rendered frame = prepared.frame(System.currentTimeMillis());
            assertEquals(1, frame.spans().size());
            TemporaryHologramDisplay temporary = harness.temporary("particle-pane",
                harness.at(world, 0.5D, 193.3D, 5.5D), Long.MAX_VALUE);
            temporary.viewers().whitelist();
            temporary.viewers().add(viewer.uuid);
            temporary.setStyle(settings.style());
            temporary.setParticleLayers(settings.particleLayers());
            temporary.setRenderedLines(List.of(frame.text().split("\\n", -1)));
            List<ParticleTextSpan> spans = new ArrayList<>();
            for (ParticleText.Span span : frame.spans()) {
                spans.add(new ParticleTextSpan(span.name(), span.start(), span.end()));
            }
            temporary.setRenderedParticleText(frame.text(), spans);
            temporary.bindRenderedFrames(now -> List.of(prepared.frame(now).text().split("\\n", -1)));
            cacheParticle(harness.gloss.particles(), settings.particleLayers().getFirst().particle());
            temporary.drive(true);
            harness.driveTemporaryParticles();
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
            assertFalse(viewer.particleLocations.isEmpty());
            assertTrue(viewer.particleLocations.stream().anyMatch(location -> Math.abs(location.getX()) < 4
                && location.getY() > 192 && Math.abs(location.getZ() - 5) < 4));
        }
    }

    @SuppressWarnings("unchecked")
    private static void cacheParticle(ParticleService service, ParticleLayer.ParticleSpec spec) throws Exception {
        Class<?> resolvedClass = Class.forName("art.arcane.gloss.particle.ParticleService$ResolvedParticle");
        Constructor<?> constructor = resolvedClass.getDeclaredConstructor(Particle.class, Object.class);
        constructor.setAccessible(true);
        Object resolved = constructor.newInstance(Particle.DUST,
            new Particle.DustOptions(Color.fromRGB(0xFF9900), 0.5F));
        Field particles = ParticleService.class.getDeclaredField("particles");
        particles.setAccessible(true);
        Map<ParticleLayer.ParticleSpec, Object> cache = (Map<ParticleLayer.ParticleSpec, Object>) particles.get(service);
        cache.put(spec, resolved);
    }
}
