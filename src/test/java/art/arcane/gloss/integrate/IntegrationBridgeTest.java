package art.arcane.gloss.integrate;

import art.arcane.gloss.GlossConfig;
import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSnapshot;
import art.arcane.volmlib.integration.IntegrationSnapshotProvider;
import art.arcane.volmlib.integration.IntegrationMetricType;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import org.junit.jupiter.api.Test;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationBridgeTest {

    private static class FakeContract implements IntegrationServiceContract {
        private final String pluginId;
        private final Set<String> keys;
        private final boolean accepts;
        private final Map<String, Double> values = new HashMap<>();
        private Set<String> lastRequested = Set.of();
        private int sampleCalls;
        private int handshakes;
        private long sampledAtMs;
        private boolean includeUnrequested;
        private boolean fail;
        private Runnable beforeSample = () -> { };

        private FakeContract(String pluginId, boolean accepts, String... keys) {
            this.pluginId = pluginId;
            this.accepts = accepts;
            this.keys = new LinkedHashSet<>(List.of(keys));
        }

        @Override
        public String pluginId() {
            return pluginId;
        }

        @Override
        public String pluginVersion() {
            return "1.0.0";
        }

        @Override
        public Set<IntegrationProtocolVersion> supportedProtocols() {
            return IntegrationBridge.SUPPORTED_PROTOCOLS;
        }

        @Override
        public Set<String> capabilities() {
            return IntegrationBridge.CAPABILITIES;
        }

        @Override
        public Set<IntegrationMetricDescriptor> metricDescriptors() {
            Set<IntegrationMetricDescriptor> descriptors = new LinkedHashSet<>();
            for (String key : keys) {
                descriptors.add(descriptor(key));
            }
            return descriptors;
        }

        @Override
        public IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
            handshakes++;
            return new IntegrationHandshakeResponse(pluginId, pluginVersion(), accepts,
                accepts ? new IntegrationProtocolVersion(1, 1) : null,
                supportedProtocols(), capabilities(), accepts ? "ok" : "denied", 0L);
        }

        @Override
        public IntegrationHeartbeat heartbeat() {
            return new IntegrationHeartbeat(new IntegrationProtocolVersion(1, 1), true, 0L, "ok");
        }

        @Override
        public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
            sampleCalls++;
            beforeSample.run();
            if (fail) {
                throw new IllegalStateException("failed provider");
            }
            lastRequested = Set.copyOf(metricKeys);
            Map<String, IntegrationMetricSample> out = new HashMap<>();
            for (String key : metricKeys) {
                Double value = values.get(key);
                out.put(key, value == null
                    ? IntegrationMetricSample.unavailable(descriptor(key), "no-value", 0L)
                    : IntegrationMetricSample.available(descriptor(key), value, sampledAtMs));
            }
            if (includeUnrequested) {
                out.put("foreign.value", IntegrationMetricSample.available(descriptor("foreign.value"), 99D, sampledAtMs));
            }
            return out;
        }

        private static IntegrationMetricDescriptor descriptor(String key) {
            return new IntegrationMetricDescriptor(key, IntegrationMetricType.DOUBLE, "", Map.of());
        }
    }

    private static final class SnapshotContract extends FakeContract implements IntegrationSnapshotProvider {
        private IntegrationMetricSnapshot snapshot = new IntegrationMetricSnapshot(0, 0, Map.of());
        private Runnable beforeSnapshot = () -> { };
        private Set<String> requested = Set.of();

        private SnapshotContract() {
            super("snapshot", true, "snapshot.value");
        }

        @Override
        public IntegrationMetricSnapshot snapshotMetrics(Set<String> keys) {
            requested = Set.copyOf(keys);
            beforeSnapshot.run();
            return snapshot;
        }

        @Override
        public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> keys) {
            throw new AssertionError("Snapshot provider must not be sampled synchronously");
        }

        private void publish(long generation, long capturedAt, double value) {
            snapshot = new IntegrationMetricSnapshot(generation, capturedAt, Map.of("snapshot.value",
                IntegrationMetricSample.available(FakeContract.descriptor("snapshot.value"), value, capturedAt)));
        }
    }

    @Test
    void sparseDemandDoesNotTraverseTheProvidersWholeMetricSchema() {
        Set<String> schema = new AbstractSet<>() {
            @Override
            public int size() {
                return 100000;
            }

            @Override
            public boolean contains(Object key) {
                return "wanted".equals(key);
            }

            @Override
            public Iterator<String> iterator() {
                throw new AssertionError("Sparse demand must not enumerate the provider schema");
            }
        };
        assertEquals(Set.of("wanted"), IntegrationBridge.intersect(schema, Set.of("wanted", "missing")));
    }

    @Test
    void snapshotConsumptionRecordsDemandAndExpiresOriginalCapture() {
        SnapshotContract source = new SnapshotContract();
        IntegrationBridge bridge = bridge();
        bridge.configure(new GlossConfig.Integration(20, 100, 0, 1, "warming"));
        bridge.adopt(List.of(source));
        bridge.render("snapshot.value", 0);
        bridge.sample(0);
        assertEquals(Set.of("snapshot.value"), source.requested);
        assertEquals("warming", bridge.render("snapshot.value", 0));
        assertEquals(IntegrationBridge.SamplingMode.SNAPSHOT, bridge.samplingModes().get("snapshot"));
        source.publish(1, 10, 7);
        bridge.sample(20);
        assertEquals("7", bridge.render("snapshot.value", 20));
        bridge.sample(90);
        assertEquals("warming", bridge.render("snapshot.value", 111));
        bridge.sample(120);
        assertTrue(bridge.published().isEmpty());
    }

    @Test
    void regressingSnapshotIsRejectedAcrossConfigurationReload() {
        SnapshotContract source = new SnapshotContract();
        IntegrationBridge bridge = bridge();
        GlossConfig.Integration policy = new GlossConfig.Integration(20, 1000, 0, 1, "");
        bridge.configure(policy);
        bridge.adopt(List.of(source));
        bridge.render("snapshot.value", 10);
        source.publish(5, 10, 5);
        bridge.sample(10);
        bridge.configure(policy);
        source.publish(4, 11, 4);
        bridge.sample(11);
        assertTrue(bridge.published().isEmpty());
        source.publish(6, 12, 6);
        bridge.sample(12);
        assertEquals("6", bridge.render("snapshot.value", 12));
    }

    @Test
    void unavailableSnapshotRetentionDoesNotExtendTheOriginalAgeLimit() {
        SnapshotContract source = new SnapshotContract();
        IntegrationBridge bridge = bridge();
        bridge.configure(new GlossConfig.Integration(20, 100, 500, 1, ""));
        bridge.adopt(List.of(source));
        bridge.render("snapshot.value", 10);
        source.publish(1, 10, 7);
        bridge.sample(20);
        source.snapshot = new IntegrationMetricSnapshot(2, 30, Map.of());
        bridge.sample(30);
        assertEquals("7", bridge.render("snapshot.value", 30));
        bridge.sample(111);
        assertTrue(bridge.published().isEmpty());
    }

    @Test
    void providerReplacementInvalidatesLateSnapshotAndAcceptsNewSequence() {
        SnapshotContract old = new SnapshotContract();
        SnapshotContract replacement = new SnapshotContract();
        old.publish(50, 10, 50);
        replacement.publish(1, 11, 1);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(old));
        bridge.render("snapshot.value", 10);
        old.beforeSnapshot = () -> bridge.adopt(List.of(replacement));
        bridge.sample(10);
        assertTrue(bridge.published().isEmpty());
        bridge.sample(11);
        assertEquals("1", bridge.render("snapshot.value", 11));
    }

    @Test
    void rediscoveringSameProviderPreservesSequenceButReplacementClearsCachedValues() {
        SnapshotContract source = new SnapshotContract();
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(source));
        bridge.render("snapshot.value", 10);
        source.publish(5, 10, 5);
        bridge.sample(10);
        bridge.adopt(List.of(source));
        assertEquals("5", bridge.render("snapshot.value", 10));
        source.publish(4, 11, 4);
        bridge.sample(11);
        assertTrue(bridge.published().isEmpty());
        source.publish(6, 12, 6);
        bridge.sample(12);
        bridge.adopt(List.of(new SnapshotContract()));
        assertTrue(bridge.published().isEmpty());
    }

    @Test
    void capabilityWithoutSnapshotInterfaceRemainsExplicitlySynchronous() {
        FakeContract legacy = new FakeContract("legacy", true, "legacy.value");
        legacy.values.put("legacy.value", 3D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(legacy));
        bridge.render("legacy.value", 0);
        bridge.sample(1);
        assertEquals(1, legacy.sampleCalls);
        assertEquals(IntegrationBridge.SamplingMode.SYNCHRONOUS, bridge.samplingModes().get("legacy"));
    }

    private static IntegrationBridge bridge() {
        return new IntegrationBridge("gloss", "3.0.0", new MetricReferences(64, 60000L));
    }

    @Test
    void aSampleCompletingAfterClearCannotRepublishItsValues() throws Exception {
        FakeContract source = new FakeContract("adapt", true, "adapt.value");
        source.values.put("adapt.value", 3D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(source));
        bridge.render("adapt.value", 0);

        blockedSample(bridge, source, bridge::clear);

        assertTrue(bridge.published().isEmpty());
        assertTrue(bridge.allKeys().isEmpty());
    }

    @Test
    void rediscoveryDuringSamplingCannotRestoreARemovedProvider() throws Exception {
        FakeContract source = new FakeContract("adapt", true, "adapt.value");
        source.values.put("adapt.value", 3D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(source));
        bridge.render("adapt.value", 0);

        blockedSample(bridge, source, () -> bridge.adopt(List.of()));

        assertTrue(bridge.published().isEmpty());
    }

    @Test
    void reconfiguringTheSamePolicyInvalidatesAnInFlightSample() throws Exception {
        FakeContract source = new FakeContract("adapt", true, "adapt.value");
        source.values.put("adapt.value", 3D);
        IntegrationBridge bridge = bridge();
        GlossConfig.Integration policy = new GlossConfig.Integration(20, 0, 0, 100, "offline");
        bridge.configure(policy);
        bridge.adopt(List.of(source));
        bridge.render("adapt.value", 0);

        blockedSample(bridge, source, () -> bridge.configure(policy));

        assertTrue(bridge.published().isEmpty());
        assertEquals("offline", bridge.render("adapt.value", 1));
    }

    @Test
    void anOldFailedSampleCannotRestoreRetryBackoffAfterReload() throws Exception {
        FakeContract source = new FakeContract("adapt", true, "adapt.value");
        source.fail = true;
        IntegrationBridge bridge = bridge();
        GlossConfig.Integration policy = new GlossConfig.Integration(20, 0, 0, 100, "");
        bridge.configure(policy);
        bridge.adopt(List.of(source));
        bridge.render("adapt.value", 0);

        blockedSample(bridge, source, () -> bridge.configure(policy));
        source.fail = false;
        source.values.put("adapt.value", 4D);
        bridge.sample(2);

        assertEquals(2, source.sampleCalls);
        assertEquals("4", bridge.render("adapt.value", 2));
    }

    private static void blockedSample(IntegrationBridge bridge, FakeContract source, Runnable transition)
        throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.beforeSample = () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Provider sample did not resume");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> sample = executor.submit(() -> bridge.sample(1));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                transition.run();
            } finally {
                release.countDown();
            }
            sample.get(5, TimeUnit.SECONDS);
        } finally {
            source.beforeSample = () -> { };
        }
    }

    @Test
    void unrequestedProviderValuesCannotEnterThePublishedSnapshot() {
        FakeContract source = new FakeContract("adapt", true, "adapt.value");
        source.values.put("adapt.value", 2D);
        source.includeUnrequested = true;
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(source));
        bridge.render("adapt.value", 0L);
        bridge.sample(1L);
        assertEquals(Map.of("adapt.value", 2D), bridge.published());
    }

    @Test
    void sampleAgeAndUnavailableRetentionBothBoundCachedValues() {
        FakeContract source = new FakeContract("adapt", true, "adapt.value");
        source.values.put("adapt.value", 2D);
        source.sampledAtMs = 100;
        IntegrationBridge bridge = bridge();
        bridge.configure(new GlossConfig.Integration(20, 200, 100, 2, "offline"));
        bridge.adopt(List.of(source));
        bridge.render("adapt.value", 100);
        bridge.sample(100);
        source.values.clear();
        bridge.sample(150);
        assertEquals("2", bridge.render("adapt.value", 200));
        assertEquals("offline", bridge.render("adapt.value", 201));
        assertTrue(bridge.previewValues("adapt", 201).isEmpty());
        source.values.put("adapt.value", 3D);
        bridge.sample(301);
        assertTrue(bridge.published().isEmpty());
        source.sampledAtMs = 301;
        bridge.sample(301);
        assertEquals("3", bridge.render("adapt.value", 501));
        assertEquals("offline", bridge.render("adapt.value", 502));
    }

    @Test
    void failedProvidersBackOffAndPolicyReloadClearsTheirRetryState() {
        FakeContract source = new FakeContract("adapt", true, "adapt.value");
        source.fail = true;
        IntegrationBridge bridge = bridge();
        GlossConfig.Integration policy = new GlossConfig.Integration(20, 0, 0, 2, "");
        bridge.configure(policy);
        bridge.adopt(List.of(source));
        bridge.render("adapt.value", 0);
        bridge.sample(0);
        bridge.sample(99);
        assertEquals(1, source.sampleCalls);
        bridge.sample(100);
        assertEquals(2, source.sampleCalls);
        source.fail = false;
        source.values.put("adapt.value", 4D);
        bridge.configure(policy);
        bridge.sample(101);
        assertEquals(3, source.sampleCalls);
        assertEquals("4", bridge.render("adapt.value", 101));
    }

    @Test
    void anAcceptedContractContributesItsDescriptorKeys() {
        FakeContract adapt = new FakeContract("adapt", true, "adapt.player-sessions", "adapt.minions");
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(adapt));

        assertEquals(1, adapt.handshakes);
        assertEquals(List.of("adapt"), bridge.pluginIds());
        assertEquals(Set.of("adapt.player-sessions", "adapt.minions"), bridge.allKeys());
        assertEquals(Set.of("adapt"), bridge.namespaces());
    }

    @Test
    void aDeclinedHandshakeAndTheRequesterItselfAreBothIgnored() {
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(
            new FakeContract("iris", false, "iris.generation-time"),
            new FakeContract("gloss", true, "gloss.menus-open")
        ));

        assertEquals(List.of(), bridge.pluginIds());
        assertEquals(Set.of(), bridge.allKeys());
    }

    @Test
    void onlyReferencedKeysAreSampled() {
        FakeContract adapt = new FakeContract("adapt", true, "adapt.player-sessions", "adapt.minions");
        adapt.values.put("adapt.player-sessions", 12.0D);
        adapt.values.put("adapt.minions", 4.0D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(adapt));

        bridge.sample(0L);
        assertEquals(0, adapt.sampleCalls);

        assertEquals("", bridge.render("adapt.player-sessions", 0L));
        bridge.sample(1L);

        assertEquals(1, adapt.sampleCalls);
        assertEquals(Set.of("adapt.player-sessions"), adapt.lastRequested);
        assertEquals("12", bridge.render("adapt.player-sessions", 2L));
        assertEquals("", bridge.render("adapt.minions", 2L));
    }

    @Test
    void referencesExpireSoAnUnusedMetricStopsBeingSampled() {
        FakeContract adapt = new FakeContract("adapt", true, "adapt.player-sessions");
        adapt.values.put("adapt.player-sessions", 3.0D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(adapt));

        bridge.render("adapt.player-sessions", 0L);
        bridge.sample(1L);
        assertEquals("3", bridge.render("adapt.player-sessions", 1L));

        bridge.sample(200000L);
        assertEquals(1, adapt.sampleCalls);
        assertTrue(bridge.published().isEmpty());
    }

    @Test
    void unavailableSamplesAreNotPublished() {
        FakeContract iris = new FakeContract("iris", true, "iris.generation-time");
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(iris));

        bridge.render("iris.generation-time", 0L);
        bridge.sample(1L);

        assertTrue(bridge.published().isEmpty());
        assertEquals("", bridge.render("iris.generation-time", 1L));
    }

    @Test
    void previewValuesArePublishedUnderTheirNativeDottedKeysAndMarkTheKeysReferenced() {
        FakeContract adapt = new FakeContract("adapt", true, "adapt.player-sessions");
        adapt.values.put("adapt.player-sessions", 9.0D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(adapt));

        assertTrue(bridge.previewValues("adapt", 0L).isEmpty());
        bridge.sample(1L);

        assertEquals(Map.of("player-sessions", 9.0D), bridge.previewValues("adapt", 1L));
    }

    @Test
    void rediscoveryDropsSamplesBelongingToAContractThatWentAway() {
        FakeContract adapt = new FakeContract("adapt", true, "adapt.player-sessions");
        adapt.values.put("adapt.player-sessions", 5.0D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(adapt));
        bridge.render("adapt.player-sessions", 0L);
        bridge.sample(1L);
        assertFalse(bridge.published().isEmpty());

        bridge.adopt(List.of());
        assertTrue(bridge.published().isEmpty());
        assertEquals(Set.of(), bridge.allKeys());
    }

    @Test
    void displayStringsAreFormattedAtSampleTimeAndReusedUntilTheNextSample() {
        FakeContract adapt = new FakeContract("adapt", true, "adapt.player-sessions");
        adapt.values.put("adapt.player-sessions", 1500.0D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(adapt));

        bridge.render("adapt.player-sessions", 0L);
        bridge.sample(1L);
        assertEquals("1.5K", bridge.render("adapt.player-sessions", 1L));

        adapt.values.put("adapt.player-sessions", 2500.0D);
        assertEquals("1.5K", bridge.render("adapt.player-sessions", 1L));

        bridge.sample(2L);
        assertEquals("2.5K", bridge.render("adapt.player-sessions", 2L));
    }

    @Test
    void rediscoveryKeepsDisplayStringsForRetainedKeys() {
        FakeContract adapt = new FakeContract("adapt", true, "adapt.player-sessions");
        FakeContract iris = new FakeContract("iris", true, "iris.generation-time");
        adapt.values.put("adapt.player-sessions", 7.0D);
        iris.values.put("iris.generation-time", 42.0D);
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(adapt, iris));
        bridge.render("adapt.player-sessions", 0L);
        bridge.render("iris.generation-time", 0L);
        bridge.sample(1L);

        bridge.adopt(List.of(adapt));

        assertEquals("7", bridge.render("adapt.player-sessions", 1L));
        assertEquals("", bridge.render("iris.generation-time", 1L));
    }

    @Test
    void aThrowingContractIsSkippedWithoutTakingTheBridgeDown() {
        IntegrationBridge bridge = bridge();
        bridge.adopt(List.of(new IntegrationServiceContract() {
            @Override
            public String pluginId() {
                return "broken";
            }

            @Override
            public String pluginVersion() {
                return "1.0.0";
            }

            @Override
            public Set<IntegrationProtocolVersion> supportedProtocols() {
                return IntegrationBridge.SUPPORTED_PROTOCOLS;
            }

            @Override
            public Set<String> capabilities() {
                return IntegrationBridge.CAPABILITIES;
            }

            @Override
            public Set<IntegrationMetricDescriptor> metricDescriptors() {
                return Set.of(new IntegrationMetricDescriptor("broken.value", IntegrationMetricType.DOUBLE, "", Map.of()));
            }

            @Override
            public IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
                return new IntegrationHandshakeResponse("broken", "1.0.0", true,
                    new IntegrationProtocolVersion(1, 1), supportedProtocols(), capabilities(), "ok", 0L);
            }

            @Override
            public IntegrationHeartbeat heartbeat() {
                return new IntegrationHeartbeat(new IntegrationProtocolVersion(1, 1), true, 0L, "ok");
            }

            @Override
            public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
                throw new IllegalStateException("sampler exploded");
            }
        }));

        bridge.render("broken.value", 0L);
        bridge.sample(1L);

        assertTrue(bridge.published().isEmpty());
        assertEquals(Set.of("broken.value"), bridge.allKeys());
    }
}
