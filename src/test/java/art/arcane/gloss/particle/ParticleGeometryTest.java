package art.arcane.gloss.particle;

import art.arcane.gloss.api.ParticleLayer;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleGeometryTest {
    @Test
    void lineSamplingIncludesBothEndpointsAndHonorsLimit() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("line",
            new Vector(), new Vector(1.0D, 0.0D, 0.0D), null,
            null, null, null, null, 0.25D);

        List<Vector> complete = ParticleGeometrySampler.sample(geometry, List.of(), 20);
        List<Vector> limited = ParticleGeometrySampler.sample(geometry, List.of(), 3);

        assertEquals(5, complete.size());
        assertEquals(new Vector(), complete.getFirst());
        assertEquals(new Vector(1.0D, 0.0D, 0.0D), complete.getLast());
        assertEquals(3, limited.size());
    }

    @Test
    void cuboidSamplesEveryAxisAndHonorsBudget() {
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("cuboid", null, null, null,
            null, null, null, 0.0D, 0.25D);
        ParticleRect box = new ParticleRect(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

        List<Vector> samples = ParticleGeometrySampler.sample(geometry, List.of(box), 24);

        assertEquals(24, samples.size());
        assertTrue(samples.stream().anyMatch(point -> point.getZ() < 0.0D));
        assertTrue(samples.stream().anyMatch(point -> point.getZ() > 0.0D));
    }

    @Test
    void textLayoutFindsColoredSpanAndIndividualLetters() {
        ParticleText.Rendered rendered = ParticleText.render(
            "this is: <particles:green>&4GREEN</particles> Colored!",
            value -> value.replace("&4", "\u00a74"));

        List<ParticleRect> span = ParticleTextLayout.bounds(rendered, "green", 1.0D, false);
        List<ParticleRect> letters = ParticleTextLayout.bounds(rendered, "green", 1.0D, true);

        assertEquals(1, span.size());
        assertEquals(5, letters.size());
        assertEquals(0.5D, span.getFirst().width(), 1.0E-9D);
    }
}
