package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The proxy's {@code connections.json}: the join, server-switch and leave messages the network
 * broadcasts. Each section is enabled/show/presentation/variants like a tablist section, plus an
 * {@code audience} of {@code network} (every proxy player) or {@code server} (players sharing a
 * backend with the subject; for a switch, either side).
 */
public final class ProxyConnectionDocuments {
    public static final List<String> AUDIENCES = List.of("network", "server");
    public static final String NETWORK = "network";
    public static final String SERVER = "server";
    public static final Document DISABLED = new Document(ProxyText.parseExpression("false"),
        Section.DISABLED, Section.DISABLED, Section.DISABLED);
    private static final int SCHEMA_VERSION = 1;

    private ProxyConnectionDocuments() {
    }

    public static Document load(Path directory) throws IOException {
        JsonObject document = ProxyDocuments.read(directory.resolve("connections.json"), SCHEMA_VERSION);
        if (document.isEmpty()) {
            return DISABLED;
        }
        return new Document(ProxyDocuments.expression(document, "show", "true"), section(document, "join"),
            section(document, "switch"), section(document, "leave"));
    }

    private static Section section(JsonObject document, String key) {
        if (!document.has(key)) {
            return Section.DISABLED;
        }
        JsonObject section = ProxyDocuments.object(document, key);
        String audience = ProxyDocuments.string(section, "audience", NETWORK);
        if (!AUDIENCES.contains(audience)) {
            throw new IllegalArgumentException("Connection audience must be one of " + AUDIENCES + ": " + audience);
        }
        return new Section(ProxyDocuments.bool(section, "enabled", true),
            ProxyDocuments.expression(section, "show", "true"), audience,
            presentation(ProxyDocuments.object(section, "presentation")),
            ProxyDocuments.variants(section, ProxyConnectionDocuments::presentation));
    }

    private static Presentation presentation(JsonObject object) {
        return new Presentation(ProxyDocuments.string(object, "text", ""));
    }

    public record Document(Expr show, Section join, Section switched, Section leave) {
    }

    public record Section(boolean enabled, Expr show, String audience, Presentation presentation,
                          List<ProxyDocuments.Variant<Presentation>> variants) {
        public static final Section DISABLED = new Section(false, ProxyText.parseExpression("false"), "network",
            new Presentation(""), List.of());
    }

    public record Presentation(String text) {
    }
}
