package art.arcane.gloss.integrate;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public final class IntegrationBridge {
    public static final Set<IntegrationProtocolVersion> SUPPORTED_PROTOCOLS = Set.of(
        new IntegrationProtocolVersion(1, 0),
        new IntegrationProtocolVersion(1, 1)
    );
    public static final Set<String> CAPABILITIES = Set.of("handshake", "heartbeat", "metrics");

    private final String requesterId;
    private final String requesterVersion;
    private final MetricReferences references;
    private final List<Source> sources;
    private volatile Map<String, Double> samples;

    public record Source(IntegrationServiceContract contract, String pluginId, Set<String> keys) {
    }

    public IntegrationBridge(String requesterId, String requesterVersion, MetricReferences references) {
        this.requesterId = requesterId;
        this.requesterVersion = requesterVersion == null ? "" : requesterVersion;
        this.references = references;
        this.sources = new CopyOnWriteArrayList<>();
        this.samples = Map.of();
    }

    public void adopt(Collection<IntegrationServiceContract> contracts) {
        List<Source> discovered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IntegrationServiceContract contract : contracts) {
            Source source = handshake(contract);
            if (source == null || !seen.add(source.pluginId())) {
                continue;
            }
            discovered.add(source);
        }

        sources.clear();
        sources.addAll(discovered);
        samples = filtered(samples, allKeys());
    }

    public void clear() {
        sources.clear();
        references.clear();
        samples = Map.of();
    }

    public Set<String> allKeys() {
        Set<String> keys = new TreeSet<>();
        for (Source source : sources) {
            keys.addAll(source.keys());
        }
        return Set.copyOf(keys);
    }

    public Set<String> namespaces() {
        Set<String> found = new TreeSet<>();
        for (Source source : sources) {
            for (String key : source.keys()) {
                found.add(namespaceOf(key, source.pluginId()));
            }
        }
        return Set.copyOf(found);
    }

    public List<String> pluginIds() {
        List<String> ids = new ArrayList<>(sources.size());
        for (Source source : sources) {
            ids.add(source.pluginId());
        }
        return List.copyOf(ids);
    }

    public String render(String key, long nowMs) {
        references.reference(key, nowMs);
        return MetricFormat.compact(samples.get(key));
    }

    public Map<String, Object> previewValues(String namespace, long nowMs) {
        Map<String, Double> published = samples;
        Map<String, Object> out = new HashMap<>();
        String prefix = namespace + ".";
        for (Source source : sources) {
            for (String key : source.keys()) {
                if (!namespaceOf(key, source.pluginId()).equals(namespace)) {
                    continue;
                }
                references.reference(key, nowMs);
                Double value = published.get(key);
                if (value != null) {
                    out.put(key.startsWith(prefix) ? key.substring(prefix.length()) : key, value);
                }
            }
        }
        return out;
    }

    public void sample(long nowMs) {
        Set<String> active = references.active(nowMs);
        if (active.isEmpty()) {
            samples = Map.of();
            return;
        }

        Map<String, Double> next = new LinkedHashMap<>();
        for (Source source : sources) {
            Set<String> wanted = intersect(source.keys(), active);
            if (wanted.isEmpty()) {
                continue;
            }
            collect(source, wanted, next);
        }
        samples = Map.copyOf(next);
    }

    public Map<String, Double> published() {
        return samples;
    }

    public static String namespaceOf(String key, String pluginId) {
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : pluginId;
    }

    public static Set<String> intersect(Set<String> keys, Set<String> active) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String key : keys) {
            if (active.contains(key)) {
                wanted.add(key);
            }
        }
        return Set.copyOf(wanted);
    }

    private void collect(Source source, Set<String> wanted, Map<String, Double> out) {
        Map<String, IntegrationMetricSample> sampled;
        try {
            sampled = source.contract().sampleMetrics(wanted);
        } catch (Throwable failure) {
            warn(source.pluginId(), "sampleMetrics", failure);
            return;
        }
        if (sampled == null) {
            return;
        }

        for (Map.Entry<String, IntegrationMetricSample> entry : sampled.entrySet()) {
            IntegrationMetricSample sample = entry.getValue();
            if (entry.getKey() == null || sample == null || !sample.available()) {
                continue;
            }
            Double value = sample.numericValue();
            if (value != null && Double.isFinite(value)) {
                out.put(entry.getKey(), value);
            }
        }
    }

    private Source handshake(IntegrationServiceContract contract) {
        if (contract == null) {
            return null;
        }

        try {
            String pluginId = normalize(contract.pluginId());
            if (pluginId.isEmpty() || pluginId.equals(requesterId)) {
                return null;
            }

            IntegrationHandshakeResponse response = contract.handshake(new IntegrationHandshakeRequest(
                requesterId,
                requesterVersion,
                SUPPORTED_PROTOCOLS,
                CAPABILITIES,
                System.currentTimeMillis()
            ));
            if (response == null || !response.accepted()) {
                Gloss.log(Level.FINE, "Integration bridge: %s declined the handshake (%s)",
                    pluginId, response == null ? "no response" : response.message());
                return null;
            }

            Set<String> keys = descriptorKeys(contract);
            if (keys.isEmpty()) {
                return null;
            }

            Gloss.log(Level.INFO, "Integration bridge: %s v%s accepted on protocol %s with %d metrics",
                pluginId, response.responderVersion(),
                response.negotiatedProtocol() == null ? "unknown" : response.negotiatedProtocol().asText(),
                keys.size());
            return new Source(contract, pluginId, keys);
        } catch (Throwable failure) {
            warn("unknown", "handshake", failure);
            return null;
        }
    }

    private static Set<String> descriptorKeys(IntegrationServiceContract contract) {
        Set<IntegrationMetricDescriptor> descriptors = contract.metricDescriptors();
        if (descriptors == null || descriptors.isEmpty()) {
            return Set.of();
        }

        Set<String> keys = new TreeSet<>();
        for (IntegrationMetricDescriptor descriptor : descriptors) {
            if (descriptor != null && descriptor.key() != null && !descriptor.key().isBlank()) {
                keys.add(descriptor.key());
            }
        }
        return Set.copyOf(keys);
    }

    private static Map<String, Double> filtered(Map<String, Double> current, Set<String> keys) {
        Map<String, Double> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : current.entrySet()) {
            if (keys.contains(entry.getKey())) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(kept);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void warn(String pluginId, String stage, Throwable failure) {
        String message = failure.getMessage();
        Gloss.log(Level.WARNING, "Integration bridge: %s %s failed: %s%s", pluginId, stage,
            failure.getClass().getSimpleName(), message == null || message.isEmpty() ? "" : ": " + message);
    }
}
