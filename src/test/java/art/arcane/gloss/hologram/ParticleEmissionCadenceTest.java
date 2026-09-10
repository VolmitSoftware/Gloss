package art.arcane.gloss.hologram;

import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.particle.ParticleService;
import org.bukkit.Particle;
import org.bukkit.util.Vector;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleEmissionCadenceTest {
    @TempDir
    File dataFolder;

    @Test
    void skippedClockSlotsDoNotStarveTheSteadyFrame() throws Exception {
        try (Fixture fixture = new Fixture(dataFolder)) {
            Object source = new Object();
            ParticleLayer layer = layer("frame", 4);
            List<Long> emittedTicks = new ArrayList<>();
            for (long tick = 1L; tick <= 21L; tick += 2L) {
                int previous = fixture.viewer.particleLocations.size();
                fixture.emit(source, layer, tick);
                if (fixture.viewer.particleLocations.size() > previous) {
                    emittedTicks.add(tick);
                }
            }
            assertEquals(List.of(1L, 5L, 9L, 13L, 17L, 21L), emittedTicks);
        }
    }

    @Test
    void missedIntervalsResumeOnceWithoutACatchUpBurst() throws Exception {
        try (Fixture fixture = new Fixture(dataFolder)) {
            Object source = new Object();
            ParticleLayer layer = layer("frame", 4);
            fixture.emit(source, layer, 1L);
            fixture.emit(source, layer, 101L);
            fixture.emit(source, layer, 101L);

            assertEquals(2, fixture.viewer.particleLocations.size());
            assertFalse(fixture.service.isDue(fixture.viewer.proxy, source, layer, 104L));
            assertTrue(fixture.service.isDue(fixture.viewer.proxy, source, layer, 105L));
        }
    }

    @Test
    void sourcesComponentsLayersAndViewersKeepIndependentCadence() throws Exception {
        try (Fixture fixture = new Fixture(dataFolder)) {
            Object first = new Object();
            Object second = new Object();
            ParticleLayer frame = layer("frame", 4);
            ParticleLayer accent = layer("accent", 4);
            fixture.emit(first, frame, 1L);
            fixture.emit(second, frame, 1L);
            fixture.emit(first, accent, 1L);
            fixture.emit(first, frame, 1L);

            CharacterizationHarness.PlayerHandle other = fixture.harness.join("Other", fixture.world, 0, 64, 0);
            fixture.service.emit(other.proxy, first, fixture.frame, frame, List.of(), 1L);

            assertEquals(3, fixture.viewer.particleLocations.size());
            assertEquals(1, other.particleLocations.size());
        }
    }

    @Test
    void returningInRangeCanEmitWithoutWaitingForAnotherInterval() throws Exception {
        try (Fixture fixture = new Fixture(dataFolder)) {
            Object source = new Object();
            ParticleLayer layer = layer("frame", 4);
            fixture.viewer.location.setX(1000D);
            fixture.emit(source, layer, 1L);
            assertTrue(fixture.viewer.particleLocations.isEmpty());

            fixture.viewer.location.setX(0D);
            fixture.emit(source, layer, 2L);
            assertEquals(1, fixture.viewer.particleLocations.size());
        }
    }

    @Test
    void everyTickTrailsAndBackwardClockChangesKeepEmitting() throws Exception {
        try (Fixture fixture = new Fixture(dataFolder)) {
            Object source = new Object();
            ParticleLayer layer = layer("trail", 1);
            fixture.emit(source, layer, 100L);
            fixture.emit(source, layer, 101L);
            fixture.emit(source, layer, 102L);
            fixture.emit(source, layer, 1L);
            fixture.emit(source, layer, 1L);
            assertEquals(4, fixture.viewer.particleLocations.size());
        }
    }

    @Test
    void quittingOrClearingTheServiceDiscardsCadence() throws Exception {
        try (Fixture fixture = new Fixture(dataFolder)) {
            Object source = new Object();
            ParticleLayer layer = layer("frame", 200);
            fixture.emit(source, layer, 1L);
            assertFalse(fixture.service.isDue(fixture.viewer.proxy, source, layer, 2L));

            fixture.service.prune(fixture.viewer.uuid);
            fixture.emit(source, layer, 2L);
            assertEquals(2, fixture.viewer.particleLocations.size());

            fixture.service.clear();
            assertTrue(fixture.service.isDue(fixture.viewer.proxy, source, layer, 3L));
        }
    }

    @Test
    void geometryParticlesHaveNoAddedSpreadOrLaunchSpeed() throws Exception {
        try (Fixture fixture = new Fixture(dataFolder)) {
            fixture.emit(new Object(), layer("frame", 4), 1L);
            List<Object> call = fixture.viewer.particleCalls.getFirst();
            assertEquals(8, call.size());
            assertEquals(1, call.get(2));
            for (int index = 3; index <= 6; index++) {
                assertEquals(0.0D, call.get(index));
            }
            assertNull(call.get(7));
        }
    }

    private static ParticleLayer layer(String id, int intervalTicks) {
        return EntityOverlayDoc.parse("particles.json", """
            {"schemaVersion":2,"revision":1,"particleLayers":[{
              "id":"%s","target":{"scope":"local"},"geometry":{"type":"point"},
              "particle":{"key":"minecraft:end_rod"},
              "emission":{"intervalTicks":%d,"pattern":"steady"}}]}
            """.formatted(id, intervalTicks)).particleLayers().getFirst();
    }

    private static final class Fixture implements AutoCloseable {
        private final CharacterizationHarness harness;
        private final CharacterizationHarness.WorldState world;
        private final CharacterizationHarness.PlayerHandle viewer;
        private final ParticleService service;
        private final ParticleFrame frame;

        @SuppressWarnings("unchecked")
        private Fixture(File folder) throws Exception {
            harness = new CharacterizationHarness(folder);
            world = harness.world("world");
            viewer = harness.join("Viewer", world, 0, 64, 0);
            service = harness.gloss.particles();
            frame = new ParticleFrame(harness.at(world, 0D, 65D, 2D),
                new Vector(1D, 0D, 0D), new Vector(0D, 1D, 0D), new Vector(0D, 0D, 1D));
            Class<?> resolvedClass = Class.forName("art.arcane.gloss.particle.ParticleService$ResolvedParticle");
            Constructor<?> constructor = resolvedClass.getDeclaredConstructor(Particle.class, Object.class);
            constructor.setAccessible(true);
            Field particles = ParticleService.class.getDeclaredField("particles");
            particles.setAccessible(true);
            Map<ParticleLayer.ParticleSpec, Object> cache =
                (Map<ParticleLayer.ParticleSpec, Object>) particles.get(service);
            cache.put(layer("frame", 4).particle(), constructor.newInstance(Particle.END_ROD, null));
        }

        private void emit(Object source, ParticleLayer layer, long tick) {
            service.emit(viewer.proxy, source, frame, layer, List.of(), tick);
        }

        @Override
        public void close() {
            harness.close();
        }
    }
}
