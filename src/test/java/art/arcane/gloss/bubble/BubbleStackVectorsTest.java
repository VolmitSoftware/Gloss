package art.arcane.gloss.bubble;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BubbleStackVectorsTest {
    private static final double TOLERANCE = 1.0E-9D;

    @Test
    void replaysAllSharedVectorsAgainstBubbleStackMath() {
        JsonObject fixture = loadFixture();
        long maxAliveMs = fixture.get("maxAliveMs").getAsLong();
        JsonArray vectors = fixture.getAsJsonArray("vectors");

        assertEquals(120, vectors.size());

        for (int index = 0; index < vectors.size(); index++) {
            JsonObject vector = vectors.get(index).getAsJsonObject();
            double spread = vector.get("spread").getAsDouble();
            int lineIndex = vector.get("lineIndex").getAsInt();
            int liveCount = vector.get("liveCount").getAsInt();
            long elapsedMs = vector.get("elapsedMs").getAsLong();
            boolean flyAway = vector.get("flyAway").getAsBoolean();
            long remainingMs = maxAliveMs - elapsedMs;
            String label = "vector[" + index + "]";

            assertEquals(vector.get("stackOffset").getAsDouble(),
                BubbleStackMath.stackOffset(spread, lineIndex, liveCount), TOLERANCE, label + " stackOffset");
            assertEquals(vector.get("flyAwayLift").getAsDouble(),
                flyAway ? BubbleStackMath.flyAway(remainingMs) : 0.0D, TOLERANCE, label + " flyAwayLift");
            assertEquals(vector.get("offsetY").getAsDouble(),
                BubbleStackMath.offsetY(spread, lineIndex, liveCount, remainingMs, flyAway), TOLERANCE,
                label + " offsetY");
        }
    }

    private static JsonObject loadFixture() {
        try (InputStream stream = Objects.requireNonNull(
            BubbleStackVectorsTest.class.getResourceAsStream("/bubble_stack_vectors.json"))) {
            String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
