package art.arcane.gloss.hologram;

import art.arcane.gloss.bedrock.BedrockPolicy;
import art.arcane.gloss.bedrock.BedrockService;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.Sent;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text displays do not render on Bedrock, so a Bedrock viewer must never be part of a hologram
 * audience: alone in range it keeps the display from spawning at all, and beside a Java viewer it
 * is absent from the animator's recipient list.
 */
class BedrockHologramAudienceTest {
    private static final UUID BEDROCK = new UUID(0L, 7L);
    private static final UUID JAVA = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    File directory;

    private static void hideHolograms(CharacterizationHarness harness) throws ReflectiveOperationException {
        harness.configure(file -> file.bedrock.detection = "uuid");
        BedrockService service = new BedrockService(BedrockService.Detection.UUID);
        CharacterizationSupport.setField(harness.gloss, "bedrock", service);
        CharacterizationSupport.setField(harness.gloss, "bedrockPolicy",
            new BedrockPolicy(service, () -> harness.config.modules().bedrock()));
    }

    @Test
    void aTemporaryHologramWithOnlyBedrockViewersNeverSpawns() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            hideHolograms(harness);
            WorldState world = harness.world("world");
            harness.join("Bedrock", BEDROCK, world, 0, 64, 0);

            TemporaryHologramDisplay temporary = harness.temporary("bedrock-only",
                harness.at(world, 0, 64, 0), 60_000L);
            temporary.setLines(List.of("hi"));
            temporary.drive(true);
            temporary.drive(true);

            assertEquals(List.of(), world.spawned,
                "a display nobody can render must not be spawned");
        }
    }

    @Test
    void animatedTemporaryHologramsTargetOnlyTheJavaViewer() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            hideHolograms(harness);
            WorldState world = harness.world("world");
            PlayerHandle bedrock = harness.join("Bedrock", BEDROCK, world, 0, 64, 0);
            PlayerHandle java = harness.join("Java", JAVA, world, 1, 64, 0);

            TemporaryHologramDisplay temporary = harness.temporary("mixed",
                harness.at(world, 0, 64, 0), 60_000L);
            temporary.setLines(List.of(CharacterizationHarness.FAST_CLIP_LINE));
            temporary.drive(true);
            temporary.drive(true);
            assertEquals(1, harness.animator.pass(0L));

            List<String> recipients = new ArrayList<>();
            for (Sent sent : harness.sender.sent) {
                for (org.bukkit.entity.Player viewer : sent.viewers()) {
                    recipients.add(viewer.getName());
                }
            }
            assertTrue(recipients.contains(java.name), "the Java viewer must receive the animation");
            assertTrue(!recipients.contains(bedrock.name), "the Bedrock viewer must receive nothing");
        }
    }
}
