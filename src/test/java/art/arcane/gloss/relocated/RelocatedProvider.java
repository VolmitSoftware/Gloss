package art.arcane.gloss.relocated;

import art.arcane.gloss.relocated.integration.IntegrationServiceContract;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RelocatedProvider implements IntegrationServiceContract {
    public static final String SERVICE_CLASS_NAME = IntegrationServiceContract.class.getName();

    private final String pluginId;
    private final boolean accepts;
    private final Set<String> keys;
    private final Map<String, Double> values;

    private Set<String> lastRequested;
    private Set<Protocol> lastOffered;
    private int handshakes;
    private int sampleCalls;

    public RelocatedProvider(String pluginId, boolean accepts, String... keys) {
        this.pluginId = pluginId;
        this.accepts = accepts;
        this.keys = new LinkedHashSet<>(List.of(keys));
        this.values = new HashMap<>();
        this.lastRequested = Set.of();
        this.lastOffered = Set.of();
    }

    public RelocatedProvider value(String key, double value) {
        values.put(key, value);
        return this;
    }

    public Set<String> lastRequested() {
        return lastRequested;
    }

    public Set<Protocol> lastOffered() {
        return lastOffered;
    }

    public int handshakes() {
        return handshakes;
    }

    public int sampleCalls() {
        return sampleCalls;
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public String pluginVersion() {
        return "9.9.9";
    }

    @Override
    public Set<Protocol> supportedProtocols() {
        return Set.of(new Protocol(1, 1), new Protocol(2, 0));
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("handshake", "metrics");
    }

    @Override
    public Set<Descriptor> metricDescriptors() {
        Set<Descriptor> descriptors = new LinkedHashSet<>();
        for (String key : keys) {
            descriptors.add(descriptor(key));
        }
        return descriptors;
    }

    @Override
    public HandshakeResponse handshake(HandshakeRequest request) {
        handshakes++;
        lastOffered = Set.copyOf(request.supportedProtocols());
        Protocol negotiated = accepts ? new Protocol(1, 1) : null;
        return new HandshakeResponse(
            pluginId,
            pluginVersion(),
            accepts,
            negotiated,
            supportedProtocols(),
            capabilities(),
            accepts ? "ok" : "denied",
            0L
        );
    }

    @Override
    public Heartbeat heartbeat() {
        return new Heartbeat(new Protocol(1, 1), true, 0L, "ok");
    }

    @Override
    public Map<String, Sample> sampleMetrics(Set<String> metricKeys) {
        sampleCalls++;
        lastRequested = Set.copyOf(metricKeys);
        Map<String, Sample> out = new LinkedHashMap<>();
        for (String key : metricKeys) {
            Double value = values.get(key);
            out.put(key, value == null
                ? new Sample(descriptor(key), null, false, 0L, "no-value")
                : new Sample(descriptor(key), value, true, 0L, ""));
        }
        return out;
    }

    private static Descriptor descriptor(String key) {
        return new Descriptor(key, Type.DOUBLE, "count", Map.of("plugin", "relocated"));
    }
}
