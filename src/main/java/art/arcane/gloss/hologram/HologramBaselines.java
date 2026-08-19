package art.arcane.gloss.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class HologramBaselines {
    public static final String RESOURCE = "/baselines/hologram.json";

    private HologramBaselines() {
    }

    public static List<String> defaultLines() {
        return baseline().lines();
    }

    public static HologramDoc baseline() {
        try (InputStream stream = HologramBaselines.class.getResourceAsStream(RESOURCE)) {
            InputStream required = Objects.requireNonNull(stream, "missing " + RESOURCE);
            String raw = new String(required.readAllBytes(), StandardCharsets.UTF_8);
            return HologramDoc.parse("hologram.json", raw);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read " + RESOURCE, failure);
        }
    }
}
