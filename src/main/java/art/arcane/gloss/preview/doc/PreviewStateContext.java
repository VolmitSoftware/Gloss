package art.arcane.gloss.preview.doc;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.PreviewStateProvider;
import art.arcane.gloss.api.PreviewStateProviders;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * {@link ExprScope} over one live preview target: the block/entity/inventory being previewed, the
 * document's own {@code vars}, and every registered {@link PreviewStateProvider} namespace.
 *
 * <p>Variable resolution order:
 * <ol>
 *   <li>{@code vars.<name>} reads the injected variables map. Variables are reachable only under
 *       that prefix, so a document variable can never shadow (or be shadowed by) an adapter name.</li>
 *   <li>adapter and provider names read the cached target snapshot.</li>
 *   <li>remaining names fall through to Gloss's standard time, server, player, PAPI and metric
 *       scope. Player values are absent only when this context was intentionally built without a
 *       viewer.</li>
 * </ol>
 * Unknown names return null; {@code ExprEvaluator} turns that into an error naming the variable.
 *
 * <p>The snapshot is sampled lazily on the first lookup and re-sampled whenever the world game
 * time changes, so one preview refresh reads each Bukkit getter once no matter how many
 * expressions reference it. Contexts without a world (a bare ender-chest inventory, or
 * {@link #statics}) fall back to a wall-clock tick counter.
 *
 * <p><b>Publication contract.</b> A context is constructed on whichever thread opened the preview
 * but sampled from the region thread that owns the target, and Folia can move a region between
 * threads across the four-tick refresh interval. The tick and the map it produced are therefore held
 * together in one immutable {@link Sampled} pair behind a single {@code volatile} reference: one
 * write per sample, one read per lookup. That makes publication atomic — a reader can never pair one
 * sample's tick with another sample's map, which two independent {@code volatile} fields could not
 * guarantee once more than one thread samples (writer A's map could land between writer B's map and
 * B's tick).
 *
 * <p>Concurrent sampling is last-writer-wins, and benignly so: both writers read the same block at
 * the same game tick, so they produce equivalent maps and whichever pair survives is correct. The
 * map is fully populated before the pair is constructed and is never mutated afterwards, so a reader
 * holding an older pair still sees a coherent, self-consistent sample.
 */
public final class PreviewStateContext implements ExprScope {

  private static final String VARS_PREFIX = "vars.";
  private static final String LANG_ARG_PREFIX = "arg";
  private static final long MILLIS_PER_TICK = 50L;

  /** Room for the widest built-in group set (universal + inventory + furnace) without a resize. */
  private static final int SNAPSHOT_CAPACITY = 24;

  /** Evaluation errors carry no source position; see ExprEvaluator's class-level note. */
  private static final int NO_POSITION = -1;

  /** Namespaces already warned about, so a rejected provider logs once rather than every refresh. */
  private static final Set<String> WARNED_NAMESPACES = ConcurrentHashMap.newKeySet();

  /**
   * Function names {@link #call} resolves itself before falling back to {@link ExprFunctions}.
   * Exposed for {@code VariableCatalogSyncTest}, which pins the shipped variable catalog's
   * {@code functions} section against this set.
   */
  static final Set<String> CONTEXT_FUNCTIONS = Set.of(
      "lang", "count", "occupied", "item", "papi", "papiNumber", "metric");

  private final Block block;
  private final Entity entity;
  private final Player player;
  private final Inventory inventory;
  private final Map<String, Object> vars;
  private final String category;
  private final TimeFlowTracker flow;
  private final World world;
  private final ExprScope standardScope;

  /** The one field carrying both halves of a sample; see the publication contract in the javadoc. */
  private volatile Sampled sampled;

  private PreviewStateContext(
      Block block,
      Entity entity,
      Player player,
      Inventory inventory,
      String category,
      Map<String, Object> vars,
      World world
  ) {
    this.block = block;
    this.entity = entity;
    this.player = player;
    this.inventory = inventory;
    this.category = category;
    this.vars = vars == null ? Map.of() : vars;
    this.flow = PreviewStateAdapters.tracksTimeFlow(category)
        ? new TimeFlowTracker(PreviewStateAdapters.countsDown(category))
        : null;
    this.world = world;
    Gloss active = Gloss.instance;
    TextPipeline text = active == null ? null : active.text();
    this.standardScope = text == null ? null : text.expressionScope(player);
  }

  public static PreviewStateContext forBlock(Block block, Player player, Map<String, Object> vars) {
    Objects.requireNonNull(block, "block");
    PreviewStateAdapters.Selection selection = PreviewStateAdapters.selectBlock(block, player);
    return new PreviewStateContext(block, null, player, selection.inventory(), selection.category(), vars, block.getWorld());
  }

  public static PreviewStateContext forEntity(Entity entity, Player player, Map<String, Object> vars) {
    Objects.requireNonNull(entity, "entity");
    PreviewStateAdapters.Selection selection = PreviewStateAdapters.selectEntity(entity);
    return new PreviewStateContext(null, entity, player, selection.inventory(), selection.category(), vars, entity.getWorld());
  }

  /** Inventory-only target, e.g. a viewer's ender chest. */
  public static PreviewStateContext forInventory(Inventory inventory, Player player, Map<String, Object> vars) {
    Objects.requireNonNull(inventory, "inventory");
    return new PreviewStateContext(null, null, player, inventory, PreviewStateAdapters.CATEGORY_INVENTORY, vars, null);
  }

  /** Target-less viewerless context for document validation and console diagnostics. */
  public static PreviewStateContext statics(Map<String, Object> vars) {
    return new PreviewStateContext(null, null, null, null, PreviewStateAdapters.CATEGORY_STATIC, vars, null);
  }

  public static PreviewStateContext forViewer(Player player, Map<String, Object> vars) {
    Objects.requireNonNull(player, "player");
    return new PreviewStateContext(null, null, player, null, PreviewStateAdapters.CATEGORY_STATIC, vars, null);
  }

  public static PreviewStateContext forWorld(World world, Player player, Inventory inventory, Map<String, Object> vars) {
    return new PreviewStateContext(null, null, player, inventory,
        inventory == null ? PreviewStateAdapters.CATEGORY_STATIC : PreviewStateAdapters.CATEGORY_INVENTORY,
        vars, Objects.requireNonNull(world, "world"));
  }

  /** The previewed inventory, or null when the target has none; Slot elements require non-null. */
  public Inventory inventory() {
    return inventory;
  }

  /** The adapter category chosen at construction; see {@link PreviewStateAdapters#catalog()}. */
  String category() {
    return category;
  }

  // ---------------------------------------------------------------------
  // ExprScope
  // ---------------------------------------------------------------------

  @Override
  public Object variable(String dottedName) {
    if (dottedName.startsWith(VARS_PREFIX)) {
      return vars.get(dottedName.substring(VARS_PREFIX.length()));
    }
    Object value = snapshot().get(dottedName);
    if (value != null || standardScope == null) {
      return value;
    }
    return standardScope.variable(dottedName);
  }

  @Override
  public Object call(String name, List<Object> args) {
    return switch (name) {
      case "lang" -> lang(args);
      case "count" -> count(args);
      case "occupied" -> occupied(args);
      case "item" -> item(args);
      default -> standardScope == null ? ExprFunctions.call(name, args) : standardScope.call(name, args);
    };
  }

  // ---------------------------------------------------------------------
  // Snapshot
  // ---------------------------------------------------------------------

  /** One tick and the map sampled at it, so the two can only ever be published together. */
  record Sampled(long tick, Map<String, Object> values) {
  }

  private Map<String, Object> snapshot() {
    long tick = currentTick();
    Sampled cached = sampled;
    if (cached != null && cached.tick() == tick) {
      return cached.values();
    }
    Map<String, Object> values = new HashMap<>(SNAPSHOT_CAPACITY);
    values.put("world.name", world == null ? "" : world.getName());
    values.put("world.time", world == null ? 0.0D : (double) world.getTime());
    PreviewStateAdapters.sample(category, block, entity, inventory, flow, tick, values);
    List<PreviewStateProvider> providers = PreviewStateProviders.all();
    if (!providers.isEmpty()) {
      mergeProviders(providers, values);
    }
    sampled = new Sampled(tick, values);
    return values;
  }

  private long currentTick() {
    return world == null ? System.currentTimeMillis() / MILLIS_PER_TICK : world.getGameTime();
  }

  private void mergeProviders(List<PreviewStateProvider> providers, Map<String, Object> out) {
    for (PreviewStateProvider provider : providers) {
      String namespace = null;
      Map<String, Object> values;
      try {
        namespace = provider.namespace();
        if (namespace == null || namespace.isBlank() || rejectReserved(namespace)) {
          continue;
        }
        values = provider.snapshot(block, entity, player);
      } catch (RuntimeException failure) {
        // A third-party provider must never take a preview down with it.
        warnProviderFailure(namespace, provider, failure);
        continue;
      }
      if (values == null) {
        continue;
      }
      for (Map.Entry<String, Object> entry : values.entrySet()) {
        Object value = coerce(entry.getValue());
        if (entry.getKey() != null && value != null) {
          out.put(namespace + "." + entry.getKey(), value);
        }
      }
    }
  }

  /**
   * Once-per-namespace terse warn, reusing {@link #WARNED_NAMESPACES} so a provider that is both
   * reserved and throwing does not double-log. When {@code provider.namespace()} itself is what
   * threw, there is no namespace to key on yet, so the implementing class name stands in.
   */
  private static void warnProviderFailure(String namespace, PreviewStateProvider provider, RuntimeException failure) {
    String key = namespace != null ? namespace : provider.getClass().getName();
    if (!WARNED_NAMESPACES.add(key)) {
      return;
    }
    Gloss.logExceptionStack(false, failure, "Preview provider '%s' threw; provider ignored.", key);
  }

  /**
   * True when a provider namespace would shadow a built-in variable, e.g. a provider called
   * {@code inventory} publishing {@code inventory.size}. Such a provider is dropped whole rather
   * than partially merged, and warned about once per namespace so a misconfigured plugin is
   * diagnosable without spamming the log every refresh.
   */
  private static boolean rejectReserved(String namespace) {
    if (!PreviewStateAdapters.isReservedNamespace(namespace)) {
      return false;
    }
    if (WARNED_NAMESPACES.add(namespace)) {
      Gloss.log(Level.WARNING, "Preview provider namespace '%s' is reserved by a built-in variable; provider ignored.", namespace);
    }
    return true;
  }

  /** Narrows a provider value to the expression runtime's types; anything else is dropped. */
  private static Object coerce(Object value) {
    if (value instanceof Double || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return null;
  }

  // ---------------------------------------------------------------------
  // Functions
  // ---------------------------------------------------------------------

  /**
   * {@code lang(key, ...)} resolves a message through the same chain the retired layouts used
   * ({@link GlossLocalization#globalText}), so a running plugin renders the active locale and a
   * headless test renders the English default.
   *
   * <p>The key is looked up in {@link GlossMessages#catalog()} first, because only the catalog's own
   * {@link TextKey} carries the real English template and therefore the real placeholder names.
   *
   * <p>An id the catalog does not know does <b>not</b> render as itself on a running server. The
   * synthesised {@code TextKey.of(key, key)} still has to pass through the active
   * {@code LocalizationSnapshot}, which resolves every key against the catalog and throws
   * {@code IllegalArgumentException: Unknown message key} for one it has never seen. {@code lang}
   * turns that into an {@link ExprException}, the caller reports it as a label-text build error, and
   * the label renders empty; a document that already compiled keeps its last good version, so a
   * mistyped key blanks one line rather than the preview. Only a headless call — no plugin instance,
   * therefore no snapshot — takes {@code GlossLocalization}'s English fallback and renders the id
   * back as its own text.
   */
  private String lang(List<Object> args) {
    if (args.isEmpty()) {
      throw new ExprException("lang expects at least 1 argument (the message key), got 0", NO_POSITION);
    }
    if (!(args.get(0) instanceof String key)) {
      throw new ExprException("lang argument 1 (key) must be a string", NO_POSITION);
    }
    try {
      TextKey resolved = messageKey(key);
      return LanguageAudience.call(player == null ? null : player.getUniqueId(),
          () -> GlossLocalization.globalText(resolved, langArguments(resolved, args)));
    } catch (IllegalArgumentException invalid) {
      throw new ExprException("lang: " + invalid.getMessage(), NO_POSITION);
    }
  }

  /**
   * The catalog's own key when the id is known, else a synthesised key whose English default is the
   * id itself. The synthesised key only renders on a headless call; a running server rejects it,
   * see {@link #lang}.
   */
  static TextKey messageKey(String key) {
    MessageKey known = GlossMessages.catalog().key(key);
    return known instanceof TextKey text ? text : TextKey.of(key, key);
  }

  /**
   * Binds positional call arguments onto the resolved key's own placeholder names: argument 1 fills
   * the first <code>{name}</code> in the English template, argument 2 the second, and so on. That
   * is what lets a document write {@code lang("gloss.preview.state.smelting_item", item, percent)}
   * and get {@code "Smelting Iron Ore 42%"} out of the template {@code "Smelting {item} {percent}%"}.
   *
   * <p>Arguments past the last placeholder are ignored for catalog keys so strict localization does
   * not receive unexpected names. Unknown headless-only keys retain positional names such as
   * {@code arg0}. Values are stringified with the expression language's own rule, so {@code 42.0}
   * inserts as {@code "42"}, and they are inserted as untrusted text so a container name can never
   * smuggle in colour codes.
   */
  static MessageArgs langArguments(TextKey key, List<Object> args) {
    if (args.size() <= 1) {
      return MessageArgs.empty();
    }
    List<String> placeholders = orderedPlaceholders(key.english());
    boolean knownKey = GlossMessages.catalog().key(key.id()) != null;
    int suppliedArguments = args.size() - 1;
    int boundArguments = knownKey ? Math.min(suppliedArguments, placeholders.size()) : suppliedArguments;
    MessageArgs.Builder builder = MessageArgs.builder();
    for (int position = 0; position < boundArguments; position++) {
      String name = knownKey ? placeholders.get(position) : LANG_ARG_PREFIX + position;
      builder.untrusted(name, ExprFunctions.call("str", List.of(args.get(position + 1))));
    }
    return builder.build();
  }

  /**
   * Placeholder names in first-appearance order, honouring the <code>{{</code> escape VolmLib's own
   * scanner uses. {@code TextKey.placeholders()} cannot be used here: it returns a
   * {@code Set.copyOf(...)}, which has already lost the insertion order this binding depends on.
   */
  static List<String> orderedPlaceholders(String template) {
    List<String> names = new ArrayList<>();
    int cursor = 0;
    while (cursor < template.length()) {
      int open = template.indexOf('{', cursor);
      if (open < 0) {
        break;
      }
      if (open + 1 < template.length() && template.charAt(open + 1) == '{') {
        cursor = open + 2;
        continue;
      }
      int close = template.indexOf('}', open + 1);
      if (close < 0) {
        break;
      }
      String name = template.substring(open + 1, close);
      if (!names.contains(name)) {
        names.add(name);
      }
      cursor = close + 1;
    }
    return names;
  }

  private double count(List<Object> args) {
    ItemStack stack = slotItem("count", args);
    return stack == null ? 0.0 : stack.getAmount();
  }

  private boolean occupied(List<Object> args) {
    return slotItem("occupied", args) != null;
  }

  /**
   * {@code item(slot)} is the material id in a slot ({@code "IRON_ORE"}), or the empty string when
   * the slot is empty, out of range, or the target has no inventory. Ids rather than display text,
   * matching {@code blockType}: a document that wants the name a player reads writes
   * {@code readable(item(0))}, which is the pair the retired furnace state line drew.
   */
  private String item(List<Object> args) {
    ItemStack stack = slotItem("item", args);
    return stack == null ? "" : stack.getType().name();
  }

  /** Null for a missing inventory, an out-of-range slot, or an empty stack. */
  private ItemStack slotItem(String name, List<Object> args) {
    if (args.size() != 1) {
      throw new ExprException(name + " expects 1 argument(s), got " + args.size(), NO_POSITION);
    }
    if (!(args.get(0) instanceof Double index)) {
      throw new ExprException(name + " argument 1 must be a number", NO_POSITION);
    }
    if (inventory == null) {
      return null;
    }
    int slot = (int) Math.floor(index);
    if (slot < 0 || slot >= inventory.getSize()) {
      return null;
    }
    ItemStack stack = inventory.getItem(slot);
    return PreviewStateAdapters.empty(stack) ? null : stack;
  }
}
