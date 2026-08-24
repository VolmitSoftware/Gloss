package art.arcane.gloss.api;

public interface AnchoredHologram extends Hologram {
    double scale();

    String billboard();

    double yaw();

    double pitch();

    void setScale(double scale);

    void setOrientation(String billboard, double yaw, double pitch);
}
