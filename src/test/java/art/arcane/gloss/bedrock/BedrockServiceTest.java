package art.arcane.gloss.bedrock;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockServiceTest {
    private static final UUID FLOODGATE_SHAPED = new UUID(0L, 0x1234_5678_9ABC_DEF0L);
    private static final UUID JAVA_SHAPED = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void detectionParsesLeniently() {
        assertEquals(BedrockService.Detection.AUTO, BedrockService.Detection.parse(null));
        assertEquals(BedrockService.Detection.AUTO, BedrockService.Detection.parse("nonsense"));
        assertEquals(BedrockService.Detection.FLOODGATE, BedrockService.Detection.parse(" floodgate "));
        assertEquals(BedrockService.Detection.OFF, BedrockService.Detection.parse("OFF"));
    }

    @Test
    void uuidDetectionUsesTheFloodgateShapeOnly() {
        BedrockService service = new BedrockService(BedrockService.Detection.UUID);

        assertTrue(service.isBedrock(FLOODGATE_SHAPED));
        assertFalse(service.isBedrock(JAVA_SHAPED));
        assertFalse(service.isBedrock((UUID) null));
        assertEquals("uuid", service.describe());
    }

    @Test
    void offNeverReportsBedrockAndAutoWithoutPluginsReportsNone() {
        BedrockService off = new BedrockService(BedrockService.Detection.OFF);
        assertFalse(off.isBedrock(FLOODGATE_SHAPED));
        assertEquals("off", off.describe());

        BedrockService auto = new BedrockService(BedrockService.Detection.AUTO);
        assertFalse(auto.isBedrock(FLOODGATE_SHAPED));
        assertEquals("none", auto.describe());
    }

    @Test
    void switchingDetectionDropsTheCachedProbe() {
        BedrockService service = new BedrockService(BedrockService.Detection.OFF);
        assertFalse(service.isBedrock(FLOODGATE_SHAPED));
        service.detection(BedrockService.Detection.UUID);
        assertTrue(service.isBedrock(FLOODGATE_SHAPED));
        service.detection(BedrockService.Detection.OFF);
        assertFalse(service.isBedrock(FLOODGATE_SHAPED));
    }

    @Test
    void staticEntryPointIsNullSafeWithoutAPlugin() {
        assertFalse(BedrockService.isBedrockPlayer(FLOODGATE_SHAPED));
    }
}
