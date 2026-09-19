package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.beam.BeamService;
import art.arcane.gloss.behavior.BehaviorService;
import art.arcane.gloss.camera.CameraService;
import art.arcane.gloss.chat.ChannelService;
import art.arcane.gloss.condition.ConditionHooks;
import art.arcane.gloss.connection.ConnectionsService;
import art.arcane.gloss.forge.GlyphService;
import art.arcane.gloss.glosspack.GlossPackService;
import art.arcane.gloss.glow.GlowService;
import art.arcane.gloss.history.HistoryService;
import art.arcane.gloss.interaction.InteractionHitboxService;
import art.arcane.gloss.inventory.InventoryMenuService;
import art.arcane.gloss.leaderboard.LeaderboardService;
import art.arcane.gloss.lint.WorkspaceLint;
import art.arcane.gloss.marker.MarkerService;
import art.arcane.gloss.nameplate.NameplateService;
import art.arcane.gloss.nametag.NametagService;
import art.arcane.gloss.prompt.PromptService;
import art.arcane.gloss.sky.SkyService;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.gloss.state.StateStore;
import art.arcane.gloss.strings.StringsService;
import art.arcane.gloss.surface.SurfaceService;
import art.arcane.gloss.waypoint.WaypointService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The one place a headline lane adds its service. Each lane appends inside its own anchor so
 * parallel branches never edit the same line; {@link Gloss} constructs, contributes, enables,
 * reloads and disables whatever this returns without further edits.
 */
public final class GlossLaneServices {
    private GlossLaneServices() {
    }

    public static List<GlossService> create(Gloss plugin) {
        Objects.requireNonNull(plugin, "plugin");
        List<GlossService> services = new ArrayList<>();
        // --- lane:screen ---
        services.add(new SurfaceService(plugin));
        services.add(new NametagService(plugin));

        // --- lane:chat ---
        services.add(new ChannelService(plugin));
        services.add(new StringsService(plugin));
        services.add(new LeaderboardService(plugin));

        // --- lane:forms ---
        services.add(new InventoryMenuService(plugin));
        services.add(new PromptService(plugin));

        services.add(new InteractionHitboxService(plugin));

        // --- lane:world ---
        PlayerSections worldSections = new PlayerSections(plugin.getDataFolder().toPath());
        services.add(new MarkerService(plugin, worldSections));
        services.add(new WaypointService(plugin));
        services.add(new BeamService(plugin));
        services.add(new SkyService(plugin, worldSections));
        services.add(new CameraService(plugin, worldSections));
        services.add(new NameplateService(plugin));
        services.add(new GlowService(plugin));

        // --- lane:behaviors ---
        services.add(new StateStore(plugin));
        services.add(new BehaviorService(plugin));

        // --- lane:authoring ---
        services.add(new HistoryService(plugin));
        services.add(new GlossPackService(plugin));
        services.add(new WorkspaceLint(plugin));

        // --- lane:forge ---
        services.add(new GlyphService(plugin));

        // --- lane:connections ---
        services.add(new ConnectionsService(plugin));

        // --- lane:fixes ---
        services.add(new ConditionHooks(plugin));

        return requireDistinct(services);
    }

    private static List<GlossService> requireDistinct(List<GlossService> services) {
        Set<String> names = new HashSet<>();
        for (GlossService service : services) {
            String name = Objects.requireNonNull(service.name(), "service name");
            if (name.isBlank() || !names.add(name)) {
                throw new IllegalStateException("duplicate or blank lane service name: " + name);
            }
        }
        return List.copyOf(services);
    }
}
