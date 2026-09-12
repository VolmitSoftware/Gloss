package art.arcane.gloss.bedrock;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Decides whether a surface is withheld from a viewer because the viewer is on Bedrock. Text and
 * block displays do not render there, so the packets are wasted; each surface has its own switch.
 */
public final class BedrockPolicy {
    private static final String DETECTOR_OFF = "off";
    private static final String DETECTOR_NONE = "none";

    private final BedrockService service;
    private final Supplier<GlossConfig.Bedrock> config;

    public BedrockPolicy(BedrockService service, Supplier<GlossConfig.Bedrock> config) {
        this.service = Objects.requireNonNull(service, "service");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Null-safe for render paths that run before the plugin has a policy; never hides then. */
    public static BedrockPolicy of(Gloss plugin) {
        return plugin == null ? null : plugin.bedrockPolicy();
    }

    public boolean hides(BedrockSurface surface, Player viewer) {
        return viewer != null && hides(surface, viewer.getUniqueId());
    }

    public boolean hides(BedrockSurface surface, UUID viewerId) {
        return viewerId != null && service.isBedrock(viewerId) && withheld(surface);
    }

    /**
     * True while the surface is withheld and a detector is actually loaded. A caller that would
     * otherwise have to walk every viewer to find a Bedrock one skips the walk when this is false.
     */
    public boolean mayHide(BedrockSurface surface) {
        String detector = service.describe();
        return !DETECTOR_OFF.equals(detector) && !DETECTOR_NONE.equals(detector) && withheld(surface);
    }

    private boolean withheld(BedrockSurface surface) {
        GlossConfig.Bedrock current = config.get();
        return switch (surface) {
            case HOLOGRAM -> current.hideHolograms();
            case PANEL -> current.hidePanels();
            case BUBBLE -> current.hideBubbles();
            case INDICATOR -> current.hideIndicators();
            case DROP -> current.hideDrops();
            case OVERLAY -> current.hideOverlays();
        };
    }
}
