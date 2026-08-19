package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.ConfigManager;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.menu.MenuSessionManager;
import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSchema;
import art.arcane.volmlib.integration.IntegrationProtocolNegotiator;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class GlossIntegrationService implements IntegrationServiceContract {
  private static final Set<IntegrationProtocolVersion> SUPPORTED_PROTOCOLS = Set.of(
      new IntegrationProtocolVersion(1, 0),
      new IntegrationProtocolVersion(1, 1)
  );
  private static final Set<String> CAPABILITIES = Set.of(
      "handshake",
      "heartbeat",
      "metrics"
  );

  private volatile IntegrationProtocolVersion negotiatedProtocol = new IntegrationProtocolVersion(1, 1);

  public void register() {
    Bukkit.getServicesManager().register(IntegrationServiceContract.class, this, Gloss.instance, ServicePriority.Normal);
    Gloss.log(Level.INFO, "Integration provider registered for Gloss");
  }

  public void unregister() {
    Bukkit.getServicesManager().unregister(IntegrationServiceContract.class, this);
    GlossTelemetry.clear();
  }

  @Override
  public String pluginId() {
    return "gloss";
  }

  @Override
  public String pluginVersion() {
    Gloss plugin = Gloss.instance;
    return plugin == null ? "unknown" : plugin.getDescription().getVersion();
  }

  @Override
  public Set<IntegrationProtocolVersion> supportedProtocols() {
    return SUPPORTED_PROTOCOLS;
  }

  @Override
  public Set<String> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public Set<IntegrationMetricDescriptor> metricDescriptors() {
    return IntegrationMetricSchema.descriptors().stream()
        .filter(descriptor -> descriptor.key().startsWith("gloss."))
        .collect(Collectors.toSet());
  }

  @Override
  public IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
    long now = System.currentTimeMillis();
    if (request == null) {
      return new IntegrationHandshakeResponse(
          pluginId(), pluginVersion(), false, null,
          SUPPORTED_PROTOCOLS, CAPABILITIES, "missing request", now
      );
    }

    Optional<IntegrationProtocolVersion> negotiated = IntegrationProtocolNegotiator.negotiate(
        SUPPORTED_PROTOCOLS,
        request.supportedProtocols()
    );
    if (negotiated.isEmpty()) {
      return new IntegrationHandshakeResponse(
          pluginId(), pluginVersion(), false, null,
          SUPPORTED_PROTOCOLS, CAPABILITIES, "no-common-protocol", now
      );
    }

    negotiatedProtocol = negotiated.get();
    return new IntegrationHandshakeResponse(
        pluginId(), pluginVersion(), true, negotiatedProtocol,
        SUPPORTED_PROTOCOLS, CAPABILITIES, "ok", now
    );
  }

  @Override
  public IntegrationHeartbeat heartbeat() {
    return new IntegrationHeartbeat(negotiatedProtocol, true, System.currentTimeMillis(), "ok");
  }

  @Override
  public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
    Set<String> requested = metricKeys == null || metricKeys.isEmpty()
        ? IntegrationMetricSchema.glossKeys()
        : metricKeys;
    long now = System.currentTimeMillis();
    Map<String, IntegrationMetricSample> out = new HashMap<>();

    for (String key : requested) {
      switch (key) {
        case IntegrationMetricSchema.GLOSS_SESSION_HOLDERS ->
            out.put(key, sampleService(key, now, Gloss::getSessionManager,
                "session-manager-not-ready", MenuSessionManager::holderCount));
        case IntegrationMetricSchema.GLOSS_MENUS_OPEN ->
            out.put(key, available(key, GlossTelemetry.menusOpen(), now));
        case IntegrationMetricSchema.GLOSS_PREVIEWS_OPEN ->
            out.put(key, available(key, GlossTelemetry.previewsOpen(), now));
        case IntegrationMetricSchema.GLOSS_DISPLAY_ENTITIES ->
            out.put(key, available(key, DisplayEntityManager.totalCount(), now));
        case IntegrationMetricSchema.GLOSS_DISPLAY_ENTITIES_VISIBLE ->
            out.put(key, available(key, DisplayEntityManager.visibleCount(), now));
        case IntegrationMetricSchema.GLOSS_MENU_DEFINITIONS ->
            out.put(key, sampleService(key, now, Gloss::getConfigManager,
                "config-manager-not-ready", (ConfigManager manager) -> manager.keys().size()));
        case IntegrationMetricSchema.GLOSS_PACKETS_PER_SECOND ->
            out.put(key, available(key, GlossTelemetry.packetsPerSecond(now), now));
        case IntegrationMetricSchema.GLOSS_SPAWNS_PER_SECOND ->
            out.put(key, available(key, GlossTelemetry.spawnsPerSecond(now), now));
        case IntegrationMetricSchema.GLOSS_TICK_MS ->
            out.put(key, available(key, GlossTelemetry.tickMsPerSecond(now), now));
        case IntegrationMetricSchema.GLOSS_PREVIEW_REFRESH_PER_SECOND ->
            out.put(key, available(key, GlossTelemetry.previewRefreshPerSecond(now), now));
        // Gloss does not host the editor; the shared schema key stays, permanently unavailable
        case IntegrationMetricSchema.GLOSS_BUILDER_SERVER_RUNNING ->
            out.put(key, IntegrationMetricSample.unavailable(
                IntegrationMetricSchema.descriptor(key),
                "builder-server-removed",
                now
            ));
        case IntegrationMetricSchema.GLOSS_HOLOGRAMS_ACTIVE ->
            out.put(key, sampleService(key, now, Gloss::holograms,
                "holograms-not-ready", service -> service.hologramCount()));
        case IntegrationMetricSchema.GLOSS_PANELS_ACTIVE ->
            out.put(key, sampleService(key, now, Gloss::getPanelService,
                "panels-not-ready", service -> service.size()));
        case IntegrationMetricSchema.GLOSS_BOARDS_ACTIVE ->
            out.put(key, sampleService(key, now, Gloss::boards,
                "boards-not-ready", service -> service.boards().size()));
        case IntegrationMetricSchema.GLOSS_TABLIST_PLAYERS ->
            out.put(key, sampleService(key, now, Gloss::tablist,
                "tablist-not-ready", service -> service.managedPlayerCount()));
        case IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE ->
            out.put(key, sampleService(key, now, Gloss::animations,
                "animations-not-ready", service -> service.names().size()));
        case IntegrationMetricSchema.GLOSS_BUBBLES_PER_SECOND ->
            out.put(key, available(key, GlossTelemetry.bubblesPerSecond(now), now));
        case IntegrationMetricSchema.GLOSS_INDICATORS_PER_SECOND ->
            out.put(key, available(key, GlossTelemetry.indicatorsPerSecond(now), now));
        case IntegrationMetricSchema.GLOSS_EMOJI_REPLACEMENTS_PER_SECOND ->
            out.put(key, available(key, GlossTelemetry.emojiReplacementsPerSecond(now), now));
        default -> out.put(key, IntegrationMetricSample.unavailable(
            IntegrationMetricSchema.descriptor(key),
            "unsupported-key",
            now
        ));
      }
    }

    return out;
  }

  private <T> IntegrationMetricSample sampleService(String key, long now, Function<Gloss, T> accessor,
                                                    String unavailableReason, ToDoubleFunction<T> value) {
    IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(key);
    Gloss plugin = Gloss.instance;
    T service = plugin == null ? null : accessor.apply(plugin);
    if (service == null) {
      return IntegrationMetricSample.unavailable(descriptor, unavailableReason, now);
    }

    return IntegrationMetricSample.available(descriptor, value.applyAsDouble(service), now);
  }

  private IntegrationMetricSample available(String key, double value, long now) {
    return IntegrationMetricSample.available(IntegrationMetricSchema.descriptor(key), value, now);
  }
}
