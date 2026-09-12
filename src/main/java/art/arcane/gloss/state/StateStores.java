package art.arcane.gloss.state;

/** The enabled {@link StateStore}, reached by the state actions; null while the store is down. */
public final class StateStores {
    private static volatile StateStore active;

    private StateStores() {
    }

    public static StateStore active() {
        return active;
    }

    public static void install(StateStore store) {
        active = store;
    }
}
