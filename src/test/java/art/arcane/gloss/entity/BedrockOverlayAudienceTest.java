package art.arcane.gloss.entity;

import art.arcane.gloss.bedrock.BedrockPolicy;
import art.arcane.gloss.bedrock.BedrockService;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Entity overlays are text displays above a mob's head; a Bedrock viewer renders none of them, so
 * it must never be admitted to an overlay audience while Java viewers beside it still are.
 */
class BedrockOverlayAudienceTest {
    private static final String MOBS_ONLY = """
        {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0}
        """;
    private static final UUID BEDROCK = new UUID(0L, 11L);
    private static final UUID JAVA = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    File dataFolder;

    @Test
    void bedrockViewersAreNotAdmittedToOverlayAudiences() throws Exception {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            BedrockService bedrock = new BedrockService(BedrockService.Detection.UUID);
            CharacterizationSupport.setField(harness.gloss, "bedrock", bedrock);
            CharacterizationSupport.setField(harness.gloss, "bedrockPolicy",
                new BedrockPolicy(bedrock, () -> harness.config.modules().bedrock()));

            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("Java", JAVA, world, 0, 64, 0);
            harness.join("Bedrock", BEDROCK, world, 1, 64, 0);
            harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            EntityOverlayService service = harness.service(MOBS_ONLY);

            harness.drive(service);

            assertEquals(1, harness.overlays(service).size());
            for (EntityOverlayTarget overlay : harness.overlays(service).values()) {
                assertEquals(1, overlay.audience.size(), "only the Java viewer may hold an overlay");
                assertFalse(overlay.audience.containsKey(BEDROCK), "the Bedrock viewer must be absent");
            }
        }
    }
}
