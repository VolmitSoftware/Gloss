package art.arcane.gloss.integrate;

import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricType;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationBridgeTest {

    private static final class FakeContract implements IntegrationServiceContract {
        private final String pluginId;
        private final Set<String> keys;
        private final boolean accepts;
        private final Map<String, Double> values = new HashMap<>();
        private Set<String> lastRequested = Set.of();
        private int sampleCalls;
        private int handshakes;

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
            lastRequested = Set.copyOf(metricKeys);
            Map<String, IntegrationMetricSample> out = new HashMap<>();
            for (String key : metricKeys) {
                Double value = values.get(key);
                out.put(key, value == null
                    ? IntegrationMetricSample.unavailable(descriptor(key), "no-value", 0L)
                    : IntegrationMetricSample.available(descriptor(key), value, 0L));
            }
            return out;
        }

        private static IntegrationMetricDescriptor descriptor(String key) {
            return new IntegrationMetricDescriptor(key, IntegrationMetricType.DOUBLE, "", Map.of());
        }
    }

    private static IntegrationBridge bridge() {
        return new IntegrationBridge("gloss", "3.0.0", new MetricReferences(64, 60000L));
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
