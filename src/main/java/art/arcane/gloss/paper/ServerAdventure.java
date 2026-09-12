package art.arcane.gloss.paper;

import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Reflective access to the server's own Adventure classes. The shaded jar relocates the plugin's
 * copy of {@code net.kyori}, and the relocator rewrites string constants that name that package,
 * so every class name here is derived at runtime from a Bukkit method's return type instead of
 * being spelled out. Paper-only bridges use this to build native components for APIs whose
 * signatures carry Kyori types; on Spigot {@link #available()} is false.
 */
public final class ServerAdventure {
    private static final Object LOCK = new Object();
    private static volatile Resolved resolved;
    private static volatile boolean probed;

    private ServerAdventure() {
    }

    public static boolean available() {
        return resolve() != null;
    }

    /** The server's {@code Component} interface, or null when the server has no native Adventure. */
    public static Class<?> componentClass() {
        Resolved active = resolve();
        return active == null ? null : active.componentClass();
    }

    public static Object fromMiniMessage(String miniMessage) {
        return invoke(require().miniMessageDeserialize(), miniMessage);
    }

    /** MiniMessage tag escaping done by the SERVER's parser, the one that will read the result. */
    public static String escape(String text) {
        return (String) invoke(require().miniMessageEscape(), text == null ? "" : text);
    }

    public static Object fromJson(String json) {
        return invoke(require().gsonDeserialize(), json);
    }

    public static String toJson(Object component) {
        return (String) invoke(require().gsonSerialize(), component);
    }

    public static Object key(String namespacedKey) {
        return invoke(require().keyOf(), namespacedKey);
    }

    private static Object invoke(MethodHandle handle, Object argument) {
        try {
            return handle.invoke(argument);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("server Adventure call failed", failure);
        }
    }

    private static Resolved require() {
        Resolved active = resolve();
        if (active == null) {
            throw new IllegalStateException("the server has no native Adventure API");
        }
        return active;
    }

    private static Resolved resolve() {
        if (probed) {
            return resolved;
        }
        synchronized (LOCK) {
            if (!probed) {
                resolved = load();
                probed = true;
            }
            return resolved;
        }
    }

    private static Resolved load() {
        try {
            Class<?> component = Player.class.getMethod("displayName").getReturnType();
            ClassLoader loader = component.getClassLoader();
            String textPackage = component.getPackageName();
            String adventurePackage = textPackage.substring(0, textPackage.lastIndexOf('.'));
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            Class<?> miniMessage = Class.forName(textPackage + ".minimessage.MiniMessage", true, loader);
            Object miniMessageInstance = miniMessage.getMethod("miniMessage").invoke(null);
            MethodHandle miniDeserialize = lookup.unreflect(singleArgument(miniMessage, "deserialize"))
                .bindTo(miniMessageInstance);
            MethodHandle miniEscape = lookup.unreflect(singleArgument(miniMessage, "escapeTags"))
                .bindTo(miniMessageInstance);

            Class<?> gson = Class.forName(textPackage + ".serializer.gson.GsonComponentSerializer", true, loader);
            Object gsonInstance = gson.getMethod("gson").invoke(null);
            MethodHandle gsonSerialize = lookup.unreflect(singleArgument(gson, "serialize")).bindTo(gsonInstance);
            MethodHandle gsonDeserialize = lookup.unreflect(singleArgument(gson, "deserialize")).bindTo(gsonInstance);

            Class<?> key = Class.forName(adventurePackage + ".key.Key", true, loader);
            MethodHandle keyOf = lookup.unreflect(key.getMethod("key", String.class));
            return new Resolved(component, miniDeserialize, miniEscape, gsonSerialize, gsonDeserialize, keyOf);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    private static Method singleArgument(Class<?> owner, String name) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1 && !method.isBridge()) {
                return method;
            }
        }
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "(1 argument)");
    }

    private record Resolved(Class<?> componentClass, MethodHandle miniMessageDeserialize,
                            MethodHandle miniMessageEscape, MethodHandle gsonSerialize,
                            MethodHandle gsonDeserialize, MethodHandle keyOf) {
    }
}
