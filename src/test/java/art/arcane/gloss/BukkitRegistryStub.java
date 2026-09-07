package art.arcane.gloss;

import org.bukkit.Keyed;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plants a Paper RegistryAccess before any test class loads. Bukkit's registry constants
 * ({@code Attribute.MAX_HEALTH} and friends) resolve through the service-loaded RegistryAccess,
 * which has no provider outside a running server, so the first test to touch
 * {@code org.bukkit.Registry} would poison its class initializer for every later test in the same
 * JVM. Registered through META-INF/services as a JUnit launcher session listener; harnesses that
 * run outside the launcher call {@link #install()} themselves. Only the registries in
 * {@link #ENTRY_TYPES} hand out keyed proxies; every other lookup throws the same
 * {@link NoClassDefFoundError} a poisoned Registry produced, so callers that already coped with a
 * server-less registry keep seeing what they saw.
 */
public final class BukkitRegistryStub implements LauncherSessionListener {
    private static final Map<Class<?>, Object> REGISTRIES = new ConcurrentHashMap<>();
    private static final Map<Object, Class<?>> KEY_TYPES = new ConcurrentHashMap<>();
    private static final Set<Class<?>> ENTRY_TYPES = Set.of(Attribute.class);

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        install();
    }

    public static void install() {
        try {
            Class<?> holder = Class.forName("io.papermc.paper.registry.RegistryAccessHolder");
            Field instance = holder.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            Object unsafe = unsafe();
            Object base = unsafe.getClass().getMethod("staticFieldBase", Field.class).invoke(unsafe, instance);
            long offset = (Long) unsafe.getClass().getMethod("staticFieldOffset", Field.class)
                .invoke(unsafe, instance);
            Object current = unsafe.getClass().getMethod("getObject", Object.class, long.class)
                .invoke(unsafe, base, offset);
            if (current instanceof Optional<?> planted && planted.isPresent()) {
                return;
            }
            Class<?> access = Class.forName("io.papermc.paper.registry.RegistryAccess");
            Object registryAccess = Proxy.newProxyInstance(BukkitRegistryStub.class.getClassLoader(),
                new Class<?>[]{access}, (proxy, method, args) -> switch (method.getName()) {
                    case "getRegistry" -> registry(args[0] instanceof Class<?> type ? type : typeOf(args[0]));
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RegistryAccess";
                    default -> primitiveDefault(method.getReturnType());
                });
            unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(unsafe, base, offset, Optional.of(registryAccess));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to plant a registry access holder", failure);
        }
    }

    /** One stub registry per entry type, shared by the RegistryAccess and Server proxies. */
    public static Object registry(Class<?> type) {
        return REGISTRIES.computeIfAbsent(type, BukkitRegistryStub::newRegistry);
    }

    /**
     * Paper resolves registries by {@code RegistryKey} constants; the entry type is only recorded
     * in the constant's generic signature ({@code RegistryKey<Attribute> ATTRIBUTE}), so it is read
     * back from there. Unknown keys fall back to plain keyed entries.
     */
    private static Class<?> typeOf(Object registryKey) {
        return KEY_TYPES.computeIfAbsent(registryKey, key -> {
            try {
                for (Field field : Class.forName("io.papermc.paper.registry.RegistryKey").getFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.get(null) != key) {
                        continue;
                    }
                    if (field.getGenericType() instanceof ParameterizedType parameterized
                        && parameterized.getActualTypeArguments()[0] instanceof Class<?> type) {
                        return type;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // No key constants: plain keyed entries are the best a server-less test can do.
            }
            return Keyed.class;
        });
    }

    private static Object newRegistry(Class<?> type) {
        Map<Object, Object> entries = new ConcurrentHashMap<>();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "get", "getOrThrow" -> {
                if (!ENTRY_TYPES.contains(type)) {
                    throw new NoClassDefFoundError("org.bukkit.Registry<" + type.getSimpleName()
                        + "> has no entries outside a server");
                }
                yield entries.computeIfAbsent(args[0], key -> entry(type, key));
            }
            case "stream" -> entries.values().stream();
            case "iterator" -> entries.values().iterator();
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Registry<" + type.getSimpleName() + ">";
            default -> primitiveDefault(method.getReturnType());
        };
        return Proxy.newProxyInstance(BukkitRegistryStub.class.getClassLoader(),
            new Class<?>[]{Registry.class}, handler);
    }

    private static Object entry(Class<?> type, Object key) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getKey", "getKeyOrThrow", "key" -> key;
            case "isRegistered" -> true;
            case "name", "toString", "getTranslationKey", "translationKey" -> String.valueOf(key);
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> primitiveDefault(method.getReturnType());
        };
        return Proxy.newProxyInstance(BukkitRegistryStub.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }

    private static Object unsafe() throws ReflectiveOperationException {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return theUnsafe.get(null);
    }
}
