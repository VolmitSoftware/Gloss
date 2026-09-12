package art.arcane.gloss.behavior;

import java.util.List;
import java.util.Objects;

/**
 * Why a conditional runtime picked what it picked for one viewer: every condition it consulted, in
 * the order it consulted them, with the value each returned and a mark on the one that decided the
 * outcome. {@code /gloss explain} pages this.
 */
public record ExplainReport(String kind, String id, List<Line> lines) {
    public ExplainReport {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        lines = List.copyOf(lines);
    }

    /** One consulted condition: where it lives, its source text, what it evaluated to, and whether it won. */
    public record Line(String path, String expression, String value, boolean winner) {
    }
}
