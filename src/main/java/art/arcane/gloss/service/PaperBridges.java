package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;

import java.lang.reflect.Constructor;
import java.util.Objects;
import java.util.Optional;

/**
 * The one way Spigot-safe code reaches a class under {@code art.arcane.gloss.paper}. The caller
 * names the Paper API class whose presence proves the feature exists, the bridge class to load,
 * and a Bukkit-typed contract the bridge implements; the caller never links a Paper or Kyori symbol
 * itself. Absence is silent (Spigot), a broken bridge is logged once and treated as absent.
 */
public final class PaperBridges {
    private PaperBridges() {
    }

    public static <T> Optional<T> load(String paperApiClass, String bridgeClass, Class<T> contract,
                                       Object... constructorArguments) {
        Objects.requireNonNull(paperApiClass, "paperApiClass");
        Objects.requireNonNull(bridgeClass, "bridgeClass");
        Objects.requireNonNull(contract, "contract");
        ClassLoader loader = PaperBridges.class.getClassLoader();
        try {
            Class.forName(paperApiClass, false, loader);
        } catch (ClassNotFoundException absent) {
            return Optional.empty();
        } catch (LinkageError broken) {
            Gloss.logExceptionStackThrottled(false, "paper-bridge:" + paperApiClass, broken,
                "Paper API class %s failed to link; %s stays disabled.", paperApiClass, bridgeClass);
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(bridgeClass, true, loader);
            Constructor<?> constructor = matchingConstructor(type, constructorArguments);
            return Optional.of(contract.cast(constructor.newInstance(constructorArguments)));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            Gloss.logExceptionStackThrottled(false, "paper-bridge:" + bridgeClass, failure,
                "Paper bridge %s failed to load.", bridgeClass);
            return Optional.empty();
        }
    }

    private static Constructor<?> matchingConstructor(Class<?> type, Object[] arguments) throws NoSuchMethodException {
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != arguments.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < parameters.length && matches; i++) {
                matches = arguments[i] == null ? !parameters[i].isPrimitive() : parameters[i].isInstance(arguments[i]);
            }
            if (matches) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        throw new NoSuchMethodException(type.getName() + " has no constructor for " + arguments.length + " argument(s)");
    }
}
