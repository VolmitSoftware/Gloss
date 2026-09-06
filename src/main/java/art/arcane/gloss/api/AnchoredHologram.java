package art.arcane.gloss.api;

public interface AnchoredHologram extends Hologram {
    IconDisplayStyle style();

    HologramBox box();

    double yaw();

    double pitch();

    void setStyle(IconDisplayStyle style);

    void setBox(HologramBox box);

    void setOrientation(double yaw, double pitch);
}
