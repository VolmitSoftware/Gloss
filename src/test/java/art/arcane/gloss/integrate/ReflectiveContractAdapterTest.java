package art.arcane.gloss.integrate;

import art.arcane.gloss.relocated.RelocatedProvider;
import art.arcane.gloss.relocated.integration.IntegrationServiceContract;
import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricType;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveContractAdapterTest {

    private static IntegrationHandshakeRequest request() {
        return new IntegrationHandshakeRequest("gloss", "3.0.0",
            IntegrationBridge.SUPPORTED_PROTOCOLS, IntegrationBridge.CAPABILITIES, 0L);
    }

    @Test
    void onlyClassNamesEndingInTheContractSuffixAreAdaptable() {
        assertTrue(ReflectiveContractAdapter.supports("art.arcane.adapt.util.arcane.volmlib.integration.IntegrationServiceContract"));
        assertTrue(ReflectiveContractAdapter.supports("art.arcane.react.util.arcane.volmlib.integration.IntegrationServiceContract"));
        assertTrue(ReflectiveContractAdapter.supports("art.arcane.volmlib.integration.IntegrationServiceContract"));
        assertFalse(ReflectiveContractAdapter.supports("art.arcane.volmlib.integration.IntegrationHeartbeat"));
        assertFalse(ReflectiveContractAdapter.supports("IntegrationServiceContract"));
        assertFalse(ReflectiveContractAdapter.supports(null));
    }

    @Test
    void aRelocatedContractIsAdaptedIntoTheUnrelocatedView() {
        RelocatedProvider provider = new RelocatedProvider("adapt", true, "adapt.minions", "adapt.player-sessions");
        ReflectiveContractAdapter adapter = ReflectiveContractAdapter.create(provider, RelocatedProvider.SERVICE_CLASS_NAME);

        assertEquals("adapt", adapter.pluginId());
        assertEquals("9.9.9", adapter.pluginVersion());
        assertEquals(RelocatedProvider.SERVICE_CLASS_NAME, adapter.serviceClassName());
        assertEquals(Set.of(new IntegrationProtocolVersion(1, 1), new IntegrationProtocolVersion(2, 0)),
            adapter.supportedProtocols());
        assertEquals(Set.of("handshake", "metrics"), adapter.capabilities());

        Set<String> keys = new TreeSet<>();
        for (IntegrationMetricDescriptor descriptor : adapter.metricDescriptors()) {
            keys.add(descriptor.key());
            assertEquals(IntegrationMetricType.DOUBLE, descriptor.type());
            assertEquals("count", descriptor.unit());
            assertEquals(Map.of("plugin", "relocated"), descriptor.tags());
        }
        assertEquals(Set.of("adapt.minions", "adapt.player-sessions"), keys);
    }

    @Test
    void handshakeMarshalsBothWaysAndOffersOnlyTheProtocolsBothSidesShare() {
        RelocatedProvider provider = new RelocatedProvider("adapt", true, "adapt.minions");
        ReflectiveContractAdapter adapter = ReflectiveContractAdapter.create(provider, RelocatedProvider.SERVICE_CLASS_NAME);

        IntegrationHandshakeResponse response = adapter.handshake(request());

        assertEquals(1, provider.handshakes());
        assertEquals(Set.of(new IntegrationServiceContract.Protocol(1, 1)), provider.lastOffered());
        assertTrue(response.accepted());
        assertEquals("adapt", response.responderPluginId());
        assertEquals("9.9.9", response.responderVersion());
        assertEquals(new IntegrationProtocolVersion(1, 1), response.negotiatedProtocol());
        assertEquals("ok", response.message());
    }

    @Test
    void aDeclinedRelocatedHandshakeStaysDeclined() {
        RelocatedProvider provider = new RelocatedProvider("adapt", false, "adapt.minions");
        ReflectiveContractAdapter adapter = ReflectiveContractAdapter.create(provider, RelocatedProvider.SERVICE_CLASS_NAME);

        IntegrationHandshakeResponse response = adapter.handshake(request());

        assertFalse(response.accepted());
        assertNull(response.negotiatedProtocol());
    }

    @Test
    void samplesAreTranslatedAcrossTheClassloaderBoundary() {
        RelocatedProvider provider = new RelocatedProvider("adapt", true, "adapt.minions", "adapt.player-sessions")
            .value("adapt.minions", 7.0D);
        ReflectiveContractAdapter adapter = ReflectiveContractAdapter.create(provider, RelocatedProvider.SERVICE_CLASS_NAME);

        Map<String, IntegrationMetricSample> samples = adapter.sampleMetrics(Set.of("adapt.minions", "adapt.player-sessions"));

        assertEquals(Set.of("adapt.minions", "adapt.player-sessions"), provider.lastRequested());
        assertEquals(1, provider.sampleCalls());
        assertTrue(samples.get("adapt.minions").available());
        assertEquals(7.0D, samples.get("adapt.minions").valueOr(-1.0D));
        assertEquals("adapt.minions", samples.get("adapt.minions").descriptor().key());
        assertFalse(samples.get("adapt.player-sessions").available());
        assertEquals("no-value", samples.get("adapt.player-sessions").message());
    }

    @Test
    void heartbeatCrossesTheBoundary() {
        RelocatedProvider provider = new RelocatedProvider("adapt", true, "adapt.minions");
        ReflectiveContractAdapter adapter = ReflectiveContractAdapter.create(provider, RelocatedProvider.SERVICE_CLASS_NAME);

        assertTrue(adapter.heartbeat().healthy());
        assertEquals(new IntegrationProtocolVersion(1, 1), adapter.heartbeat().protocol());
    }

    @Test
    void aMalformedRelocatedSampleDegradesToUnavailableInsteadOfThrowing() {
        MalformedProvider provider = new MalformedProvider();
        ReflectiveContractAdapter adapter = ReflectiveContractAdapter.create(provider, RelocatedProvider.SERVICE_CLASS_NAME);

        Map<String, IntegrationMetricSample> samples = adapter.sampleMetrics(Set.of("bad.integral", "bad.missing"));

        assertFalse(samples.get("bad.integral").available());
        assertEquals("sample-invalid", samples.get("bad.integral").message());
        assertFalse(samples.get("bad.missing").available());
        assertEquals("sample-null", samples.get("bad.missing").message());
    }

    @Test
    void aProviderWithoutTheContractShapeCannotBeAdapted() {
        assertThrows(IllegalStateException.class,
            () -> ReflectiveContractAdapter.create(new Object(), RelocatedProvider.SERVICE_CLASS_NAME));
        assertThrows(IllegalArgumentException.class,
            () -> ReflectiveContractAdapter.create(new RelocatedProvider("adapt", true), "com.example.NotAContract"));
    }

    public static final class MalformedProvider implements IntegrationServiceContract {
        @Override
        public String pluginId() {
            return "broken";
        }

        @Override
        public String pluginVersion() {
            return "1.0.0";
        }

        @Override
        public Set<Protocol> supportedProtocols() {
            return Set.of(new Protocol(1, 1));
        }

        @Override
        public Set<String> capabilities() {
            return Set.of("metrics");
        }

        @Override
        public Set<Descriptor> metricDescriptors() {
            return Set.of(new Descriptor("bad.integral", Type.LONG, "", Map.of()));
        }

        @Override
        public HandshakeResponse handshake(HandshakeRequest request) {
            return new HandshakeResponse("broken", "1.0.0", true, new Protocol(1, 1),
                supportedProtocols(), capabilities(), "ok", 0L);
        }

        @Override
        public Heartbeat heartbeat() {
            return new Heartbeat(new Protocol(1, 1), true, 0L, "ok");
        }

        @Override
        public Map<String, Sample> sampleMetrics(Set<String> metricKeys) {
            Map<String, Sample> out = new LinkedHashMap<>();
            out.put("bad.integral", new Sample(new Descriptor("bad.integral", Type.LONG, "", Map.of()), 1.5D, true, 0L, ""));
            out.put("bad.missing", null);
            return out;
        }
    }
}
