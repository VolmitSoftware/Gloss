package art.arcane.gloss.preview.doc;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Plumbing shared by every {@link PreviewFakes} builder: the {@link Proxy} factory, the default
 * answers for the {@code Object} methods a proxy must still honour, the invocation counter, and
 * the two Bukkit types that are concrete classes rather than interfaces and so are subclassed
 * instead of proxied.
 *
 * <p>{@link ItemStack} is constructed through its protected no-argument constructor, which is the
 * only way to obtain one headlessly (the public constructors reach the item registry). The fake
 * {@link CookingRecipe} needs a fake {@link RecipeChoice} for the same reason: the real
 * {@code MaterialChoice} touches the block registry.
 *
 * <p>Split out of {@code PreviewFakes} purely to keep both files inside the size limit.
 */
final class PreviewFakeSupport {

  private PreviewFakeSupport() {
  }

  static ItemStack stack(Material type, int amount) {
    return new FakeStack(type, amount);
  }

  static CookingRecipe<?> cookingRecipe(float experience) {
    return new FakeRecipe(experience);
  }

  static Object proxy(Class<?> type, InvocationHandler handler) {
    return proxy(new Class<?>[]{type}, handler);
  }

  static Object proxy(Class<?>[] types, InvocationHandler handler) {
    return Proxy.newProxyInstance(PreviewFakeSupport.class.getClassLoader(), types, handler);
  }

  static Object identity(Object proxy, Method method, Object[] arguments) {
    return switch (method.getName()) {
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == arguments[0];
      case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName();
      default -> throw new UnsupportedOperationException(method.getName());
    };
  }

  /** Counts fake method invocations by name so tests can prove snapshot caching. */
  static final class Calls {

    private final Map<String, Integer> counts = new HashMap<>();

    void record(String methodName) {
      counts.merge(methodName, 1, Integer::sum);
    }

    int of(String methodName) {
      return counts.getOrDefault(methodName, 0);
    }

    void reset() {
      counts.clear();
    }
  }

  private static final class FakeStack extends ItemStack {

    private final Material type;
    private final int amount;

    private FakeStack(Material type, int amount) {
      this.type = type;
      this.amount = amount;
    }

    @Override
    public Material getType() {
      return type;
    }

    @Override
    public int getAmount() {
      return amount;
    }

    @Override
    public boolean isEmpty() {
      return amount < 1;
    }

    @Override
    public boolean hasItemMeta() {
      return false;
    }

    @Override
    public ItemStack clone() {
      return new FakeStack(type, amount);
    }
  }

  private static final class FakeChoice implements RecipeChoice {

    @Override
    public ItemStack getItemStack() {
      return new FakeStack(Material.IRON_ORE, 1);
    }

    @Override
    public boolean test(ItemStack stack) {
      return true;
    }

    @Override
    public RecipeChoice clone() {
      return new FakeChoice();
    }
  }

  private static final class FakeRecipe extends CookingRecipe<FakeRecipe> {

    private FakeRecipe(float experience) {
      super(
          new NamespacedKey("gloss", "fake" + Math.abs(Float.floatToIntBits(experience))),
          new FakeStack(Material.IRON_INGOT, 1),
          new FakeChoice(),
          experience,
          200
      );
    }
  }
}
