package art.arcane.gloss.integrate;

import art.arcane.gloss.relocated.RelocatedProvider;
import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricType;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractDiscoveryTest {
    private static final String GLOSS_SERVICE = IntegrationServiceContract.class.getName();
    private static final String ADAPT_SERVICE = "art.arcane.adapt.util.arcane.volmlib.integration.IntegrationServiceContract";

    @Test
    void aRelocatedContractIsDiscoveredThroughTheReflectivePath() {
        RelocatedProvider adapt = new RelocatedProvider("adapt", true, "adapt.player-sessions");
        List<IntegrationServiceContract> contracts = new ContractDiscovery().adapt(List.of(
            new ContractDiscovery.Candidate(ADAPT_SERVICE, adapt, false)
        ));

        assertEquals(1, contracts.size());
        assertInstanceOf(ReflectiveContractAdapter.class, contracts.get(0));
        assertEquals("adapt", contracts.get(0).pluginId());
    }

    @Test
    void anUnrelocatedContractIsHandedOverUnwrapped() {
        TypedContract iris = new TypedContract("iris", "iris.generation-time");
        List<IntegrationServiceContract> contracts = new ContractDiscovery().adapt(List.of(
            new ContractDiscovery.Candidate(GLOSS_SERVICE, iris, false)
        ));

        assertEquals(1, contracts.size());
        assertSame(iris, contracts.get(0));
    }

    @Test
    void glossOwnRegistrationIsNeverAdopted() {
        List<IntegrationServiceContract> contracts = new ContractDiscovery().adapt(List.of(
            new ContractDiscovery.Candidate(GLOSS_SERVICE, new TypedContract("gloss", "gloss.menus-open"), true),
            new ContractDiscovery.Candidate(ADAPT_SERVICE, new RelocatedProvider("adapt", true, "adapt.minions"), false)
        ));

        assertEquals(List.of("adapt"), pluginIds(contracts));
    }

    @Test
    void aServiceThatIsNotAContractIsIgnored() {
        List<IntegrationServiceContract> contracts = new ContractDiscovery().adapt(List.of(
            new ContractDiscovery.Candidate("net.milkbowl.vault.permission.Permission", new Object(), false),
            new ContractDiscovery.Candidate(ADAPT_SERVICE, null, false)
        ));

        assertTrue(contracts.isEmpty());
    }

    @Test
    void anUnadaptableProviderIsIsolatedAndWarnsOnlyOnce() {
        Logger logger = Logger.getLogger("Gloss");
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);

        try {
            ContractDiscovery discovery = new ContractDiscovery();
            List<ContractDiscovery.Candidate> candidates = List.of(
                new ContractDiscovery.Candidate(ADAPT_SERVICE, new Object(), false),
                new ContractDiscovery.Candidate(GLOSS_SERVICE, new TypedContract("iris", "iris.generation-time"), false)
            );

            assertEquals(List.of("iris"), pluginIds(discovery.adapt(candidates)));
            assertEquals(List.of("iris"), pluginIds(discovery.adapt(candidates)));
            assertEquals(1, records.size());
            assertTrue(records.get(0).getMessage().contains(ADAPT_SERVICE));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void aRelocatedContractReachesTheBridgeAndPublishesItsMetric() {
        RelocatedProvider adapt = new RelocatedProvider("adapt", true, "adapt.player-sessions", "adapt.minions")
            .value("adapt.player-sessions", 12.0D);
        IntegrationBridge bridge = new IntegrationBridge("gloss", "3.0.0", new MetricReferences(64, 60000L));

        bridge.adopt(new ContractDiscovery().adapt(List.of(
            new ContractDiscovery.Candidate(GLOSS_SERVICE, new TypedContract("gloss", "gloss.menus-open"), true),
            new ContractDiscovery.Candidate(ADAPT_SERVICE, adapt, false)
        )));

        assertEquals(List.of("adapt"), bridge.pluginIds());
        assertEquals(Set.of("adapt.minions", "adapt.player-sessions"), bridge.allKeys());
        assertEquals(Set.of("adapt"), bridge.namespaces());

        assertEquals("", bridge.render("adapt.player-sessions", 0L));
        bridge.sample(1L);

        assertEquals("12", bridge.render("adapt.player-sessions", 2L));
        assertEquals(Map.of("player-sessions", 12.0D), bridge.previewValues("adapt", 2L));
    }

    private static List<String> pluginIds(List<IntegrationServiceContract> contracts) {
        List<String> ids = new ArrayList<>(contracts.size());
        for (IntegrationServiceContract contract : contracts) {
            ids.add(contract.pluginId());
        }
        return ids;
    }

    private static final class TypedContract implements IntegrationServiceContract {
        private final String pluginId;
        private final Set<String> keys;

        private TypedContract(String pluginId, String... keys) {
            this.pluginId = pluginId;
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
                descriptors.add(new IntegrationMetricDescriptor(key, IntegrationMetricType.DOUBLE, "", Map.of()));
            }
            return descriptors;
        }

        @Override
        public IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
            return new IntegrationHandshakeResponse(pluginId, "1.0.0", true,
                new IntegrationProtocolVersion(1, 1), supportedProtocols(), capabilities(), "ok", 0L);
        }

        @Override
        public IntegrationHeartbeat heartbeat() {
            return new IntegrationHeartbeat(new IntegrationProtocolVersion(1, 1), true, 0L, "ok");
        }

        @Override
        public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
            Map<String, IntegrationMetricSample> out = new LinkedHashMap<>();
            for (String key : metricKeys) {
                out.put(key, IntegrationMetricSample.unavailable(
                    new IntegrationMetricDescriptor(key, IntegrationMetricType.DOUBLE, "", Map.of()), "no-value", 0L));
            }
            return out;
        }
    }
}
