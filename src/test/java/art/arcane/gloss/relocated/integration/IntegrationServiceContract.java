package art.arcane.gloss.relocated.integration;

import java.util.Map;
import java.util.Set;

public interface IntegrationServiceContract {
    String pluginId();

    String pluginVersion();

    Set<Protocol> supportedProtocols();

    Set<String> capabilities();

    Set<Descriptor> metricDescriptors();

    HandshakeResponse handshake(HandshakeRequest request);

    Heartbeat heartbeat();

    Map<String, Sample> sampleMetrics(Set<String> metricKeys);

    enum Type {
        INTEGER,
        LONG,
        DOUBLE
    }

    record Protocol(int major, int minor) {
    }

    record HandshakeRequest(
        String requesterPluginId,
        String requesterVersion,
        Set<Protocol> supportedProtocols,
        Set<String> capabilities,
        long requestedAtMs
    ) {
    }

    record HandshakeResponse(
        String responderPluginId,
        String responderVersion,
        boolean accepted,
        Protocol negotiatedProtocol,
        Set<Protocol> supportedProtocols,
        Set<String> capabilities,
        String message,
        long respondedAtMs
    ) {
    }

    record Heartbeat(Protocol protocol, boolean healthy, long lastHeartbeatMs, String message) {
    }

    record Descriptor(String key, Type type, String unit, Map<String, String> tags) {
    }

    record Sample(Descriptor descriptor, Double numericValue, boolean available, long sampledAtMs, String message) {
    }
}
