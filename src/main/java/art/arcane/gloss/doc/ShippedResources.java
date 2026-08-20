package art.arcane.gloss.doc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Reads a resource shipped inside the jar. A missing or unreadable resource is a packaging fault
 * rather than an operator fault, so both surface as {@link IllegalStateException}.
 */
public final class ShippedResources {
    private ShippedResources() {
    }

    public static String readText(String resource) {
        try (InputStream stream = ShippedResources.class.getResourceAsStream(resource)) {
            InputStream required = Objects.requireNonNull(stream, "missing " + resource);
            return new String(required.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read " + resource, failure);
        }
    }
}
