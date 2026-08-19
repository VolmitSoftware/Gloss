package art.arcane.gloss.integrate;

import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSchema;
import art.arcane.volmlib.integration.IntegrationMetricType;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectiveContractAdapter implements IntegrationServiceContract {
    public static final String CONTRACT_SUFFIX = ".integration.IntegrationServiceContract";

    private static final MethodEntry MISSING_METHOD = new MethodEntry(null);
    private static final ClassValue<Map<String, MethodEntry>> NO_ARG_METHODS = new ClassValue<Map<String, MethodEntry>>() {
        @Override
        protected Map<String, MethodEntry> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private final Object provider;
    private final String serviceClassName;
    private final Method pluginIdMethod;
    private final Method pluginVersionMethod;
    private final Method supportedProtocolsMethod;
    private final Method capabilitiesMethod;
    private final Method metricDescriptorsMethod;
    private final Method handshakeMethod;
    private final Method heartbeatMethod;
    private final Method sampleMetricsMethod;
    private final String pluginId;
    private final String pluginVersion;

    private ReflectiveContractAdapter(Object provider, String serviceClassName) {
        Class<?> providerClass = provider.getClass();
        this.provider = provider;
        this.serviceClassName = serviceClassName;
        this.pluginIdMethod = requireMethod(providerClass, "pluginId");
        this.pluginVersionMethod = requireMethod(providerClass, "pluginVersion");
        this.supportedProtocolsMethod = requireMethod(providerClass, "supportedProtocols");
        this.capabilitiesMethod = requireMethod(providerClass, "capabilities");
        this.metricDescriptorsMethod = requireMethod(providerClass, "metricDescriptors");
        this.handshakeMethod = requireSingleArgumentMethod(providerClass, "handshake");
        this.heartbeatMethod = requireMethod(providerClass, "heartbeat");
        this.sampleMetricsMethod = requireMethod(providerClass, "sampleMetrics", Set.class);
        this.pluginId = normalize(text(invoke(pluginIdMethod), ""));
        this.pluginVersion = text(invoke(pluginVersionMethod), "");
    }

    public static boolean supports(String serviceClassName) {
        return serviceClassName != null && serviceClassName.endsWith(CONTRACT_SUFFIX);
    }

    public static ReflectiveContractAdapter create(Object provider, String serviceClassName) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider cannot be null");
        }
        if (!supports(serviceClassName)) {
            throw new IllegalArgumentException("Not an integration contract service: " + serviceClassName);
        }
        return new ReflectiveContractAdapter(provider, serviceClassName);
    }

    public String serviceClassName() {
        return serviceClassName;
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public String pluginVersion() {
        return pluginVersion;
    }

    @Override
    public Set<IntegrationProtocolVersion> supportedProtocols() {
        return toProtocols(invoke(supportedProtocolsMethod));
    }

    @Override
    public Set<String> capabilities() {
        return toStrings(invoke(capabilitiesMethod));
    }

    @Override
    public Set<IntegrationMetricDescriptor> metricDescriptors() {
        Object raw = invoke(metricDescriptorsMethod);
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }

        Set<IntegrationMetricDescriptor> descriptors = new LinkedHashSet<>();
        for (Object value : values) {
            IntegrationMetricDescriptor descriptor = toDescriptor(value, "");
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        return Set.copyOf(descriptors);
    }

    @Override
    public IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Handshake request cannot be null");
        }

        Object raw = invoke(handshakeMethod, toForeignRequest(request));
        if (raw == null) {
            return null;
        }

        return new IntegrationHandshakeResponse(
            text(read(raw, "responderPluginId"), pluginId.isEmpty() ? "unknown" : pluginId),
            text(read(raw, "responderVersion"), pluginVersion),
            bool(read(raw, "accepted")),
            toProtocol(read(raw, "negotiatedProtocol")),
            toProtocols(read(raw, "supportedProtocols")),
            toStrings(read(raw, "capabilities")),
            text(read(raw, "message"), ""),
            number(read(raw, "respondedAtMs"), System.currentTimeMillis())
        );
    }

    @Override
    public IntegrationHeartbeat heartbeat() {
        Object raw = invoke(heartbeatMethod);
        long now = System.currentTimeMillis();
        if (raw == null) {
            return new IntegrationHeartbeat(null, false, now, "heartbeat-missing");
        }

        return new IntegrationHeartbeat(
            toProtocol(read(raw, "protocol")),
            bool(read(raw, "healthy")),
            number(read(raw, "lastHeartbeatMs"), now),
            text(read(raw, "message"), "")
        );
    }

    @Override
    public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
        Object raw = invoke(sampleMetricsMethod, metricKeys);
        if (!(raw instanceof Map<?, ?> values)) {
            return Map.of();
        }

        Map<String, IntegrationMetricSample> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = text(entry.getKey(), "");
            if (key.isBlank()) {
                continue;
            }
            out.put(key, toSample(entry.getValue(), key, now));
        }
        return out;
    }

    private Object toForeignRequest(IntegrationHandshakeRequest request) {
        Set<Object> offered = new LinkedHashSet<>();
        Object rawProtocols = invoke(supportedProtocolsMethod);
        if (rawProtocols instanceof Collection<?> protocols) {
            for (Object protocol : protocols) {
                IntegrationProtocolVersion converted = toProtocol(protocol);
                if (converted != null && request.supportedProtocols().contains(converted)) {
                    offered.add(protocol);
                }
            }
        }

        Class<?> requestType = handshakeMethod.getParameterTypes()[0];
        try {
            Constructor<?> constructor = requestType.getConstructor(
                String.class,
                String.class,
                Set.class,
                Set.class,
                long.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                request.requesterPluginId(),
                request.requesterVersion(),
                offered,
                request.capabilities(),
                request.requestedAtMs()
            );
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to build a handshake request for " + serviceClassName, failure);
        }
    }

    private IntegrationMetricSample toSample(Object value, String key, long now) {
        if (value == null) {
            return IntegrationMetricSample.unavailable(descriptorFor(key), "sample-null", now);
        }

        IntegrationMetricDescriptor descriptor = toDescriptor(read(value, "descriptor"), key);
        if (descriptor == null) {
            descriptor = descriptorFor(key);
        }

        try {
            return new IntegrationMetricSample(
                descriptor,
                decimal(read(value, "numericValue")),
                bool(read(value, "available")),
                number(read(value, "sampledAtMs"), now),
                text(read(value, "message"), "")
            );
        } catch (IllegalArgumentException failure) {
            return IntegrationMetricSample.unavailable(descriptor, "sample-invalid", now);
        }
    }

    private IntegrationMetricDescriptor toDescriptor(Object value, String keyFallback) {
        if (value == null) {
            return keyFallback == null || keyFallback.isBlank() ? null : descriptorFor(keyFallback);
        }

        String key = text(read(value, "key"), keyFallback);
        if (key == null || key.isBlank()) {
            return null;
        }
        return new IntegrationMetricDescriptor(key, toType(read(value, "type")), text(read(value, "unit"), ""), toStringMap(read(value, "tags")));
    }

    private static IntegrationMetricDescriptor descriptorFor(String key) {
        try {
            return IntegrationMetricSchema.descriptor(key);
        } catch (Throwable failure) {
            return new IntegrationMetricDescriptor(key, IntegrationMetricType.DOUBLE, "", Map.of());
        }
    }

    private static IntegrationMetricType toType(Object value) {
        if (value == null) {
            return IntegrationMetricType.DOUBLE;
        }

        String name = text(read(value, "name"), String.valueOf(value));
        if (name.isBlank()) {
            return IntegrationMetricType.DOUBLE;
        }
        try {
            return IntegrationMetricType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            return IntegrationMetricType.DOUBLE;
        }
    }

    private static Set<IntegrationProtocolVersion> toProtocols(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }

        Set<IntegrationProtocolVersion> protocols = new LinkedHashSet<>();
        for (Object entry : values) {
            IntegrationProtocolVersion protocol = toProtocol(entry);
            if (protocol != null) {
                protocols.add(protocol);
            }
        }
        return Set.copyOf(protocols);
    }

    private static IntegrationProtocolVersion toProtocol(Object value) {
        if (value == null) {
            return null;
        }

        Object major = read(value, "major");
        Object minor = read(value, "minor");
        if (!(major instanceof Number majorValue) || !(minor instanceof Number minorValue)) {
            return null;
        }

        try {
            return new IntegrationProtocolVersion(majorValue.intValue(), minorValue.intValue());
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Set<String> toStrings(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }

        Set<String> out = new LinkedHashSet<>();
        for (Object entry : values) {
            String normalized = normalize(text(entry, ""));
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return Set.copyOf(out);
    }

    private static Map<String, String> toStringMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = text(entry.getKey(), "");
            if (!key.isBlank()) {
                out.put(key, text(entry.getValue(), ""));
            }
        }
        return Map.copyOf(out);
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number found ? found.longValue() : fallback;
    }

    private static Double decimal(Object value) {
        return value instanceof Number found ? found.doubleValue() : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Method requireMethod(Class<?> source, String name, Class<?>... parameters) {
        try {
            Method method = source.getMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (Throwable failure) {
            throw new IllegalStateException("Missing method " + source.getName() + "#" + name, failure);
        }
    }

    private static Method requireSingleArgumentMethod(Class<?> source, String name) {
        for (Method method : source.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("Missing method " + source.getName() + "#" + name);
    }

    private static Object read(Object target, String name) {
        if (target == null) {
            return null;
        }

        Map<String, MethodEntry> cache = NO_ARG_METHODS.get(target.getClass());
        MethodEntry entry = cache.get(name);
        if (entry == null) {
            entry = resolve(target.getClass(), name);
            cache.put(name, entry);
        }
        if (entry.method() == null) {
            return null;
        }

        try {
            return entry.method().invoke(target);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static MethodEntry resolve(Class<?> source, String name) {
        try {
            Method method = source.getMethod(name);
            method.setAccessible(true);
            return new MethodEntry(method);
        } catch (Throwable failure) {
            return MISSING_METHOD;
        }
    }

    private Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(provider, arguments);
        } catch (Throwable failure) {
            throw new IllegalStateException(
                "Reflective call " + method.getName() + " failed on " + provider.getClass().getName(), failure);
        }
    }

    private record MethodEntry(Method method) {
    }
}
