package art.arcane.gloss.particle;

import art.arcane.gloss.api.ParticleAnchor;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class WorldScopeSamplerTest {
    private static final double EPSILON = 1.0E-6D;
    private static final UUID BOSS = UUID.fromString("6a1f9f1e-0000-4000-8000-000000000001");

    @Test
    void theWorldScopeIsAcceptedOnATarget() {
        Assertions.assertEquals("world", new ParticleLayer.Target("world", null, null, null).scope());
    }

    @Test
    void aCoordinateAnchorParsesFromTheVectorArrayExistingDocumentsUse() {
        ParticleAnchor anchor = DocumentParsers.GSON.fromJson("[1, 2, 3]", ParticleAnchor.class);

        Assertions.assertEquals(new Vector(1, 2, 3), anchor.position());
        Assertions.assertNull(anchor.role());
        Assertions.assertNull(anchor.entity());
    }

    @Test
    void aRoleAnchorParsesFromAString() {
        ParticleAnchor anchor = DocumentParsers.GSON.fromJson("\"viewer\"", ParticleAnchor.class);

        Assertions.assertEquals("viewer", anchor.role());
        Assertions.assertNull(anchor.position());
    }

    @Test
    void anEntityAnchorParsesFromAnObject() {
        ParticleAnchor anchor = DocumentParsers.GSON.fromJson(
            "{\"entity\": \"6a1f9f1e-0000-4000-8000-000000000001\"}", ParticleAnchor.class);

        Assertions.assertEquals(BOSS, anchor.entity());
    }

    @Test
    void anUnknownRoleIsRefused() {
        Assertions.assertThrows(RuntimeException.class,
            () -> DocumentParsers.GSON.fromJson("\"nowhere\"", ParticleAnchor.class));
    }

    @Test
    void aWorldLineSamplesAbsoluteCoordinatesAtTheSpacing() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("line",
            ParticleAnchor.of(new Vector(0, 64, 0)), ParticleAnchor.of(new Vector(0, 64, 10)),
            null, null, null, null, null, 2.0D);

        List<Vector> points = ParticleGeometrySampler.sampleWorld(geometry, anchor -> anchor.position(), 64);

        Assertions.assertEquals(6, points.size());
        Assertions.assertEquals(64.0D, points.getFirst().getY(), EPSILON);
        Assertions.assertEquals(0.0D, points.getFirst().getZ(), EPSILON);
        Assertions.assertEquals(10.0D, points.getLast().getZ(), EPSILON);
    }

    @Test
    void aWorldPolylineSamplesEveryLeg() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("polyline", null, null,
            List.of(ParticleAnchor.of(new Vector(0, 64, 0)), ParticleAnchor.of(new Vector(0, 64, 4)),
                ParticleAnchor.of(new Vector(4, 64, 4))),
            null, null, null, null, 2.0D);

        List<Vector> points = ParticleGeometrySampler.sampleWorld(geometry, anchor -> anchor.position(), 64);

        Assertions.assertEquals(5, points.size());
        Assertions.assertEquals(new Vector(4, 64, 4), points.getLast());
    }

    @Test
    void aRoleAnchorIsResolvedByTheCaller() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("line",
            ParticleAnchor.role("viewer"), ParticleAnchor.of(new Vector(0, 64, 4)),
            null, null, null, null, null, 4.0D);

        List<Vector> points = ParticleGeometrySampler.sampleWorld(geometry,
            anchor -> anchor.role() == null ? anchor.position() : new Vector(0, 64, 0), 64);

        Assertions.assertEquals(new Vector(0, 64, 0), points.getFirst());
        Assertions.assertEquals(new Vector(0, 64, 4), points.getLast());
    }

    @Test
    void anUnresolvableAnchorSamplesNothing() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("line",
            ParticleAnchor.entity(BOSS), ParticleAnchor.of(new Vector(0, 64, 4)),
            null, null, null, null, null, 1.0D);

        Assertions.assertEquals(List.of(),
            ParticleGeometrySampler.sampleWorld(geometry, anchor -> anchor.position(), 64));
    }

    @Test
    void theMaximumCapsTheSampledPoints() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("line",
            ParticleAnchor.of(new Vector(0, 64, 0)), ParticleAnchor.of(new Vector(0, 64, 100)),
            null, null, null, null, null, 0.5D);

        Assertions.assertEquals(12,
            ParticleGeometrySampler.sampleWorld(geometry, anchor -> anchor.position(), 12).size());
    }

    @Test
    void aGeometryThatIsNotALineSamplesNothingInTheWorldFrame() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("outline", null, null, null,
            2.0D, 2.0D, null, null, 0.5D);

        Assertions.assertEquals(List.of(),
            ParticleGeometrySampler.sampleWorld(geometry, anchor -> anchor.position(), 64));
    }

    @Test
    void theLocalFrameSamplerStillWalksTheSameLine() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("line",
            ParticleAnchor.of(new Vector(0, 0, 0)), ParticleAnchor.of(new Vector(0, 0, 10)),
            null, null, null, null, null, 2.0D);

        List<Vector> local = ParticleGeometrySampler.sample(geometry, List.of(), 64);

        Assertions.assertEquals(6, local.size());
        Assertions.assertEquals(10.0D, local.getLast().getZ(), EPSILON);
    }
}
