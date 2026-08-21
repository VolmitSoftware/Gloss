package art.arcane.gloss.api;

public interface AnchoredHologram extends Hologram {
    String billboard();

    double yaw();

    double pitch();

    void setOrientation(String billboard, double yaw, double pitch);
}
