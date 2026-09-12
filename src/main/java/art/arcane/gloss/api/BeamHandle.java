package art.arcane.gloss.api;

/** A live beam. Cancelling it destroys the display for every viewer that was given one. */
public interface BeamHandle {
    void cancel();

    boolean active();
}
