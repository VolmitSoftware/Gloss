package art.arcane.gloss.integrate;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSnapshot;
import art.arcane.volmlib.integration.IntegrationSnapshotProvider;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

public final class IntegrationBridge {
    public static final Set<IntegrationProtocolVersion> SUPPORTED_PROTOCOLS = Set.of(
        new IntegrationProtocolVersion(1, 0),
        new IntegrationProtocolVersion(1, 1)
    );
    public static final Set<String> CAPABILITIES = Set.of("handshake", "heartbeat", "metrics", IntegrationSnapshotProvider.CAPABILITY);

    private static final Publication EMPTY = new Publication(Map.of(), Map.of(), Map.of());

    private final String requesterId;
    private final String requesterVersion;
    private final MetricReferences references;
    private final Object lifecycle = new Object();
    private volatile List<Source> sources;
    private Map<String, Long> retryAt = Map.of();
    private Map<String, Long> snapshotGenerations = Map.of();
    private long generation;
    private long sampleSequence;
    private volatile GlossConfig.Integration policy = new GlossConfig.Integration(20, 5000, 0, 100, "");
    private volatile Publication publication;

    public record Source(IntegrationServiceContract contract, String pluginId, Set<String> keys,
                         SamplingMode samplingMode) {
        public Source(IntegrationServiceContract contract, String pluginId, Set<String> keys) {
            this(contract, pluginId, keys, SamplingMode.SYNCHRONOUS);
        }
    }

    public enum SamplingMode {
        SNAPSHOT, SYNCHRONOUS
    }

    /** Sampled values plus their display strings, swapped together so a reader never sees a mixed pair. */
    private record Publication(Map<String, CachedMetric> metrics, Map<String, Double> values, Map<String, String> rendered) {
    }

    private record CachedMetric(double value, long sampledAt, long acceptedAt, long expiresAt) {
    }

    public IntegrationBridge(String requesterId, String requesterVersion, MetricReferences references) {
        this.requesterId = requesterId;
        this.requesterVersion = requesterVersion == null ? "" : requesterVersion;
        this.references = references;
        this.sources = List.of();
        this.publication = EMPTY;
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

        synchronized (lifecycle) {
            Map<String, Source> previous = new HashMap<>();
            for (Source source : sources) {
                previous.put(source.pluginId(), source);
            }
            Map<String, Long> retainedGenerations = new HashMap<>();
            Set<String> retainedKeys = new LinkedHashSet<>();
            for (Source source : discovered) {
                Source old = previous.get(source.pluginId());
                if (old == null || !sameProvider(old.contract(), source.contract())) {
                    continue;
                }
                retainedKeys.addAll(source.keys());
                Long sequence = snapshotGenerations.get(source.pluginId());
                if (sequence != null) {
                    retainedGenerations.put(source.pluginId(), sequence);
                }
            }
            generation++;
            sources = List.copyOf(discovered);
            retryAt = Map.of();
            snapshotGenerations = Map.copyOf(retainedGenerations);
            publication = publish(filtered(publication.metrics(), retainedKeys));
        }
    }

    public void configure(GlossConfig.Integration policy) {
        Objects.requireNonNull(policy, "policy");
        synchronized (lifecycle) {
            generation++;
            this.policy = policy;
            references.configure(policy.maxReferencedMetrics(), policy.referenceWindowMs());
            retryAt = Map.of();
            publication = EMPTY;
        }
    }

    public void clear() {
        synchronized (lifecycle) {
            generation++;
            sources = List.of();
            references.clear();
            retryAt = Map.of();
            snapshotGenerations = Map.of();
            publication = EMPTY;
        }
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

    public Map<String, SamplingMode> samplingModes() {
        Map<String, SamplingMode> modes = new LinkedHashMap<>();
        for (Source source : sources) {
            modes.put(source.pluginId(), source.samplingMode());
        }
        return Map.copyOf(modes);
    }

    public Map<String, Object> diagnosticSnapshot() {
        return Map.of("samplingModes", samplingModes(), "referencedMetrics", references.tracked(),
            "demandEvictions", references.evictions(), "maxReferencedMetrics", policy.maxReferencedMetrics(),
            "referenceWindowMs", policy.referenceWindowMs());
    }

    public long demandEvictions() {
        return references.evictions();
    }

    public String render(String key, long nowMs) {
        references.reference(key, nowMs);
        Publication current = publication;
        CachedMetric metric = current.metrics().get(key);
        return metric == null || nowMs > metric.expiresAt() ? policy.unavailableText() : current.rendered().get(key);
    }

    public Double value(String key, long nowMs) {
        references.reference(key, nowMs);
        CachedMetric metric = publication.metrics().get(key);
        return metric == null || nowMs > metric.expiresAt() ? null : metric.value();
    }

    public Map<String, Object> previewValues(String namespace, long nowMs) {
        Map<String, CachedMetric> published = publication.metrics();
        Map<String, Object> out = new HashMap<>();
        String prefix = namespace + ".";
        for (Source source : sources) {
            for (String key : source.keys()) {
                if (!namespaceOf(key, source.pluginId()).equals(namespace)) {
                    continue;
                }
                references.reference(key, nowMs);
                CachedMetric value = published.get(key);
                if (value != null && nowMs <= value.expiresAt()) {
                    out.put(key.startsWith(prefix) ? key.substring(prefix.length()) : key, value.value());
                }
            }
        }
        return out;
    }

    public void sample(long nowMs) {
        SamplePass pass;
        synchronized (lifecycle) {
            pass = new SamplePass(policy, sources, publication, new HashMap<>(retryAt), new HashMap<>(snapshotGenerations), generation,
                ++sampleSequence, nowMs);
        }
        Set<String> active = references.active(nowMs);
        GlossConfig.Integration activePolicy = pass.policy();
        Publication previous = pass.previous();
        Map<String, CachedMetric> next = new LinkedHashMap<>();
        for (Source source : pass.sources()) {
            Set<String> wanted = intersect(source.keys(), active);
            if (wanted.isEmpty()) {
                continue;
            }
            if (nowMs >= pass.retryAt().getOrDefault(source.pluginId(), Long.MIN_VALUE)) {
                collect(source, wanted, next, pass);
            }
            if (activePolicy.retainUnavailableMs() > 0) {
                for (String key : wanted) {
                    CachedMetric old = previous.metrics().get(key);
                    if (next.containsKey(key) || old == null) {
                        continue;
                    }
                    long expiresAt = Math.min(old.expiresAt(), old.acceptedAt() + activePolicy.retainUnavailableMs());
                    if (nowMs <= expiresAt) {
                        next.put(key, new CachedMetric(old.value(), old.sampledAt(), old.acceptedAt(), expiresAt));
                    }
                }
            }
        }
        Publication completed = publish(next);
        synchronized (lifecycle) {
            if (pass.generation() == generation && pass.sequence() == sampleSequence) {
                retryAt = Map.copyOf(pass.retryAt());
                snapshotGenerations = Map.copyOf(pass.snapshotGenerations());
                publication = completed;
            }
        }
    }

    public Map<String, Double> published() {
        return publication.values();
    }

    /** Display strings are formatted here, at sample time, so {@link #render} is a map lookup. */
    private static Publication publish(Map<String, CachedMetric> metrics) {
        if (metrics.isEmpty()) {
            return EMPTY;
        }

        Map<String, String> rendered = new LinkedHashMap<>();
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, CachedMetric> entry : metrics.entrySet()) {
            values.put(entry.getKey(), entry.getValue().value());
            rendered.put(entry.getKey(), MetricFormat.compact(entry.getValue().value()));
        }
        return new Publication(Map.copyOf(metrics), Map.copyOf(values), Map.copyOf(rendered));
    }

    public static String namespaceOf(String key, String pluginId) {
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : pluginId;
    }

    public static Set<String> intersect(Set<String> keys, Set<String> active) {
        Set<String> wanted = new LinkedHashSet<>();
        Set<String> smaller = keys.size() <= active.size() ? keys : active;
        Set<String> larger = smaller == keys ? active : keys;
        for (String key : smaller) {
            if (larger.contains(key)) {
                wanted.add(key);
            }
        }
        return Set.copyOf(wanted);
    }

    private void collect(Source source, Set<String> wanted, Map<String, CachedMetric> out, SamplePass pass) {
        long nowMs = pass.nowMs();
        GlossConfig.Integration activePolicy = pass.policy();
        Map<String, IntegrationMetricSample> sampled;
        try {
            if (source.samplingMode() == SamplingMode.SNAPSHOT) {
                IntegrationMetricSnapshot snapshot = ((IntegrationSnapshotProvider) source.contract()).snapshotMetrics(wanted);
                if (snapshot == null || snapshot.generation() < pass.snapshotGenerations().getOrDefault(source.pluginId(), -1L)) {
                    return;
                }
                pass.snapshotGenerations().put(source.pluginId(), snapshot.generation());
                sampled = snapshot.samples();
            } else {
                sampled = source.contract().sampleMetrics(wanted);
            }
        } catch (Throwable failure) {
            pass.retryAt().put(source.pluginId(), nowMs + activePolicy.errorRetryTicks() * 50L);
            warn(source.pluginId(), "sampleMetrics", failure);
            return;
        }
        if (sampled == null) {
            return;
        }

        for (String key : wanted) {
            IntegrationMetricSample sample = sampled.get(key);
            if (sample == null || !sample.available() || !key.equals(sample.descriptor().key())) {
                continue;
            }
            Double value = sample.numericValue();
            long timestamp = Math.min(sample.sampledAtMs(), nowMs);
            if (timestamp < 0
                || activePolicy.maxSampleAgeMs() > 0 && nowMs - timestamp > activePolicy.maxSampleAgeMs()) {
                continue;
            }
            if (value != null && Double.isFinite(value)) {
                long expiresAt = activePolicy.maxSampleAgeMs() == 0 ? Long.MAX_VALUE
                    : timestamp + activePolicy.maxSampleAgeMs();
                out.put(key, new CachedMetric(value, timestamp, nowMs, expiresAt));
            }
        }
    }

    private record SamplePass(GlossConfig.Integration policy, List<Source> sources, Publication previous,
                              Map<String, Long> retryAt, Map<String, Long> snapshotGenerations, long generation, long sequence, long nowMs) {
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

            Gloss.verbose("Integration bridge: %s v%s accepted on protocol %s with %d metrics.",
                pluginId, response.responderVersion(),
                response.negotiatedProtocol() == null ? "unknown" : response.negotiatedProtocol().asText(),
                keys.size());
            boolean snapshots = response.capabilities().contains(IntegrationSnapshotProvider.CAPABILITY)
                && contract instanceof IntegrationSnapshotProvider
                && (!(contract instanceof ReflectiveContractAdapter adapter) || adapter.supportsSnapshotMetrics());
            SamplingMode mode = snapshots ? SamplingMode.SNAPSHOT : SamplingMode.SYNCHRONOUS;
            Gloss.verbose("Integration bridge: %s uses %s metric sampling.", pluginId, mode);
            return new Source(contract, pluginId, keys, mode);
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

    private static boolean sameProvider(IntegrationServiceContract first, IntegrationServiceContract second) {
        return first == second || first instanceof ReflectiveContractAdapter left
            && second instanceof ReflectiveContractAdapter right && left.sameProvider(right);
    }

    private static Map<String, CachedMetric> filtered(Map<String, CachedMetric> current, Set<String> keys) {
        Map<String, CachedMetric> kept = new LinkedHashMap<>();
        for (Map.Entry<String, CachedMetric> entry : current.entrySet()) {
            if (keys.contains(entry.getKey())) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void warn(String pluginId, String stage, Throwable failure) {
        Gloss.logExceptionStack(false, failure, "Integration bridge: %s %s failed.", pluginId, stage);
    }
}
