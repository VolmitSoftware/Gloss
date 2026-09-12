package art.arcane.gloss.lint;

import java.util.Objects;

/**
 * One finding about one document. The {@link #wire()} shape is what the editor sync project's
 * {@code warnings} array carries and what the editor's Problems panel parses.
 */
public record Diagnostic(String code, Severity severity, String kind, String id, String pointer,
                         String message) {
    public Diagnostic {
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        kind = kind == null ? "" : kind;
        id = id == null ? "" : id;
        pointer = pointer == null ? "" : pointer;
        message = Objects.requireNonNull(message, "message");
        if (code.indexOf('|') >= 0 || kind.indexOf('|') >= 0 || id.indexOf('|') >= 0
                || pointer.indexOf('|') >= 0) {
            throw new IllegalArgumentException("diagnostic fields cannot contain a pipe");
        }
    }

    /** {@code code|kind|id|pointer|message}. */
    public String wire() {
        return code + "|" + kind + "|" + id + "|" + pointer + "|" + message.replace('|', '/');
    }
}
