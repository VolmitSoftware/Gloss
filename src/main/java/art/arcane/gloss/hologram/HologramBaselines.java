package art.arcane.gloss.hologram;

import art.arcane.gloss.doc.ShippedResources;

import java.util.List;

public final class HologramBaselines {
    public static final String RESOURCE = "/baselines/hologram.json";

    private HologramBaselines() {
    }

    public static List<String> defaultLines() {
        return baseline().lines();
    }

    public static HologramDoc baseline() {
        return HologramDoc.parse("hologram.json", ShippedResources.readText(RESOURCE));
    }
}
