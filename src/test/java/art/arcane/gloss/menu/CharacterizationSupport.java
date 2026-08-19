package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared plumbing for the Step-1 characterization suites of the menu/preview/panel domain.
 *
 * <p>Everything here exists to run the REAL engine classes headlessly with zero production-code
 * changes (Step 1 forbids seams in these files): a bare {@link Gloss} allocated without running
 * its {@code JavaPlugin} constructor (so {@code isEnabled()} is false and every
 * {@code SchedulerUtils}/{@code Events} registration no-ops), a fake {@link Server} installed into
 * {@link Bukkit} the same way {@code PanelViewSessionTest} already does, and reflection helpers
 * for the private members the pinned behaviors live behind.
 *
 * <p>Static hygiene: every installer returns the previous value and callers MUST restore it in
 * {@code @After}/{@code finally} — other test classes in this JVM rely on {@code Gloss.instance}
 * and {@code Bukkit.server} being null.
 */
public final class CharacterizationSupport {

  private CharacterizationSupport() {
  }

  // ---------------------------------------------------------------------
  // Proxy plumbing
  // ---------------------------------------------------------------------

  public static Object proxy(Class<?>[] types, InvocationHandler handler) {
    return Proxy.newProxyInstance(CharacterizationSupport.class.getClassLoader(), types, handler);
  }

  public static Object identity(Object proxy, Method method, Object[] arguments) {
    return switch (method.getName()) {
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == arguments[0];
      case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName();
      default -> throw new UnsupportedOperationException(
          "characterization fake was asked for " + method.getName());
    };
  }

  // ---------------------------------------------------------------------
  // Bukkit server install (PanelViewSessionTest pattern)
  // ---------------------------------------------------------------------

  /** A muted logger so PluginLogger/server logging never spams the test output. */
  public static Logger mutedLogger() {
    Logger logger = Logger.getLogger("gloss.characterization");
    logger.setLevel(Level.OFF);
    logger.setUseParentHandlers(false);
    return logger;
  }

  /**
   * A minimal fake server: world lookup by UUID, a muted logger, and a null-meta item factory so
   * {@link org.bukkit.inventory.ItemStack#isSimilar} works on the headless fake stacks.
   */
  public static Server server(Map<UUID, World> worlds) {
    return (Server) proxy(new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
      case "getWorld" -> args != null && args.length == 1 && args[0] instanceof UUID uuid
          ? worlds.get(uuid)
          : null;
      case "getLogger" -> mutedLogger();
      case "getItemFactory" -> itemFactory();
      case "getName", "getVersion", "getBukkitVersion" -> "characterization";
      case "isPrimaryThread" -> true;
      case "getOnlinePlayers" -> java.util.List.of();
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "toString" -> "Server[characterization]";
      default -> throw new UnsupportedOperationException("fake server was asked for " + method.getName());
    });
  }

  private static Object itemFactory() {
    return proxy(new Class<?>[]{org.bukkit.inventory.ItemFactory.class}, (proxy, method, args) -> {
      if (method.getName().equals("getItemMeta")) {
        return null;
      }
      if (method.getName().equals("equals") && method.getParameterCount() == 2) {
        return args[0] == args[1];
      }
      if (method.getReturnType() == boolean.class) {
        return false;
      }
      return identity(proxy, method, args);
    });
  }

  public static Object installServer(Server server) throws ReflectiveOperationException {
    Field field = serverField();
    Object previous = field.get(null);
    field.set(null, server);
    return previous;
  }

  public static void restoreServer(Object previous) throws ReflectiveOperationException {
    serverField().set(null, previous);
  }

  private static Field serverField() throws ReflectiveOperationException {
    Field field = Bukkit.class.getDeclaredField("server");
    field.setAccessible(true);
    return field;
  }

  // ---------------------------------------------------------------------
  // Bare Gloss plugin
  // ---------------------------------------------------------------------

  /**
   * A {@link Gloss} allocated without running any constructor. {@code isEnabled()} stays false, so
   * every scheduler/listener registration inside the engines no-ops; the description, server, and
   * logger fields are populated so {@code getLogger()}/{@code getServer()} work.
   */
  public static Gloss bareGloss(Server server) throws ReflectiveOperationException {
    Gloss gloss = (Gloss) allocate(Gloss.class);
    setField(gloss, "description",
        new PluginDescriptionFile("Gloss", "0.0-characterization", "art.arcane.gloss.Gloss"));
    setField(gloss, "server", server);
    setField(gloss, "logger", new PluginLogger(gloss));
    return gloss;
  }

  /** Publishes the bare plugin as {@code Gloss.instance}; returns the previous instance. */
  public static Gloss installGloss(Gloss gloss) {
    Gloss previous = Gloss.instance;
    Gloss.instance = gloss;
    return previous;
  }

  public static void restoreGloss(Gloss previous) {
    Gloss.instance = previous;
  }

  private static Object allocate(Class<?> type) throws ReflectiveOperationException {
    sun.reflect.ReflectionFactory factory = sun.reflect.ReflectionFactory.getReflectionFactory();
    Constructor<?> constructor =
        factory.newConstructorForSerialization(type, Object.class.getDeclaredConstructor());
    return constructor.newInstance();
  }

  // ---------------------------------------------------------------------
  // Reflection helpers
  // ---------------------------------------------------------------------

  public static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
    findField(target.getClass(), name).set(target, value);
  }

  public static Object getField(Object target, String name) throws ReflectiveOperationException {
    return findField(target.getClass(), name).get(target);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        Field field = current.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
        // walk up
      }
    }
    throw new NoSuchFieldException(type.getName() + "#" + name
        + " — a characterization pin depends on this member; if it was renamed, update the pin deliberately");
  }

  public static Object invoke(Object target, String method, Class<?>[] signature, Object... args) {
    try {
      Method found = findMethod(target.getClass(), method, signature);
      found.setAccessible(true);
      return found.invoke(target, args);
    } catch (ReflectiveOperationException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new AssertionError(target.getClass().getName() + "#" + method
          + " — a characterization pin depends on this member; if it was renamed, update the pin deliberately",
          cause);
    }
  }

  private static Method findMethod(Class<?> type, String name, Class<?>[] signature)
      throws NoSuchMethodException {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        return current.getDeclaredMethod(name, signature);
      } catch (NoSuchMethodException ignored) {
        // walk up
      }
    }
    throw new NoSuchMethodException(type.getName() + "#" + name);
  }
}
