package art.arcane.gloss.api;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParticleLayerTest {
    @Test
    void canonicalizesChoicesAndDustColor() {
        ParticleLayer layer = new ParticleLayer(" Green-Word ",
            new ParticleLayer.Target("SPAN", "Green-Word", null, null),
            new ParticleLayer.Geometry("glyphFill", null, null, null,
                null, null, null, 0.1D, 0.2D),
            null,
            new ParticleLayer.ParticleSpec("minecraft:dust", "#00FF00", 1.25D),
            null,
            5000);

        assertEquals("green-word", layer.id());
        assertEquals("span", layer.target().scope());
        assertEquals("glyphFill", layer.geometry().type());
        assertEquals("#00ff00", layer.particle().color());
        assertEquals("behind", layer.placement().layer());
        assertEquals(1000, layer.priority());
    }

    @Test
    void rejectsIncompleteTargetsAndUnsupportedParticleData() {
        assertThrows(IllegalArgumentException.class,
            () -> new ParticleLayer.Target("span", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ParticleLayer.Target("line", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ParticleLayer.ParticleSpec("minecraft:soul", "#ffffff", null));
    }

    @Test
    void geometryCopiesMutableVectorsAndLayerListsRejectDuplicateIds() {
        Vector from = new Vector(1.0D, 2.0D, 3.0D);
        ParticleLayer.Geometry geometry = new ParticleLayer.Geometry("line", from,
            new Vector(2.0D, 2.0D, 3.0D), null, null, null, null, null, null);
        from.setX(99.0D);

        assertEquals(1.0D, geometry.from().getX());

        ParticleLayer layer = new ParticleLayer("same",
            new ParticleLayer.Target("local", null, null, null), geometry, null,
            new ParticleLayer.ParticleSpec("minecraft:soul", null, null), null, 0);
        assertThrows(IllegalArgumentException.class,
            () -> ParticleLayer.copyLayers(List.of(layer, layer), "test"));
    }
}
