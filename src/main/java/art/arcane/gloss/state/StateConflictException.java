package art.arcane.gloss.state;

/** Two behavior documents declared one state key with a different scope, type or default. */
public final class StateConflictException extends IllegalStateException {
    private final String key;
    private final String documentA;
    private final String documentB;

    public StateConflictException(String key, String documentA, String documentB) {
        super("state key \"" + key + "\" is declared differently by behaviors/" + documentA
            + " and behaviors/" + documentB);
        this.key = key;
        this.documentA = documentA;
        this.documentB = documentB;
    }

    public String key() {
        return key;
    }

    public String documentA() {
        return documentA;
    }

    public String documentB() {
        return documentB;
    }
}
