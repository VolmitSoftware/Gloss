package art.arcane.gloss.hologram;

import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.entity.EntityOverlayText;
import art.arcane.gloss.particle.ParticleText;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOverlayAnimationTest {
    @TempDir
    File dataFolder;

    @Test
    void composedFramesPreserveOuterMarkupAndNeverReadLiveState() {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            AtomicBoolean capturing = new AtomicBoolean(true);
            harness.registerFunction("viewer", viewer -> {
                assertTrue(capturing.get());
                return viewer.getName();
            });
            harness.configureAnimation("fast", List.of("{{ entity.name }}", "|viewer|"));
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", harness.world("world"), 0, 64, 0);
            EntityOverlayDoc settings = EntityOverlayDoc.parse("default.json", """
                {"schemaVersion":2,"revision":1,"lines":[{
                  "id":"name","text":"<gradient:red:blue><particles:name>|animation.fast|</particles></gradient>"
                }]}
                """);
            EntityOverlayText.Snapshot snapshot = new EntityOverlayText.Snapshot(
                "<red>Literal</red>", 20, 20, 20, 0, 3, 0, 1, "zombie", 2);
            EntityOverlayText.Prepared prepared = EntityOverlayText.prepare(harness.gloss, viewer.proxy,
                settings, snapshot, List.of());
            capturing.set(false);
            viewer.locationRead = () -> { throw new AssertionError("frame read viewer state"); };

            assertTrue(prepared.animated());
            ParticleText.Rendered first = prepared.frame(0);
            ParticleText.Rendered second = prepared.frame(10);
            assertEquals("<red>Literal</red>", ChatColor.stripColor(first.text()));
            assertEquals("Viewer", ChatColor.stripColor(second.text()));
            assertEquals(1, first.spans().size());
            assertEquals(1, second.spans().size());
            assertTrue(first.text().contains("§x"));
            assertTrue(second.text().contains("§x"));
            assertEquals(second, prepared.frame(10));
        }
    }
}
