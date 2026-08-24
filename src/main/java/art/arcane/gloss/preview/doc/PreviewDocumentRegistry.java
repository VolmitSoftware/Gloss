package art.arcane.gloss.preview.doc;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.menu.MenuSessionManager;
import art.arcane.gloss.preview.doc.CompiledPreviewDocument.CompiledMatch;
import art.arcane.gloss.preview.doc.CompiledPreviewDocument.CompiledVariant;
import art.arcane.gloss.preview.doc.CompiledPreviewDocument.Resolved;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Material;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Owns every preview document on disk: extracts the shipped defaults into {@code previews/},
 * compiles them, watches the folder for edits, and answers the two questions the live plugin asks —
 * "may a raycast stop on this block?" and "which document draws this target?".
 *
 * <p><b>Snapshot publication.</b> Resolution and raycast eligibility read one immutable
 * {@link Snapshot} through a single volatile field, rebuilt whole after every successful load,
 * reload, reset, or delete. {@link #isPreviewBlockType} therefore runs on the raycast hot path
 * lock-free and allocation-free: one volatile read plus an {@link EnumSet} ordinal test. The
 * {@code EnumSet} inside a published snapshot is never mutated afterwards.
 *
 * <p><b>Resolution order.</b> The highest {@code match.priority} wins. Within one priority a
 * document that names the target exactly beats one that globs it, which beats the
 * {@code anyInventoryHolder} fallback. A genuine tie is broken by document name so the outcome is
 * stable across restarts, and warned about once per pair of documents.
 *
 * <p><b>Raycast eligibility.</b> A block or entity is eligible when some document names its type,
 * exactly or through a glob. The {@code anyInventoryHolder} fallback contributes no materials or
 * entity types; it separately admits inventory-holding carts and chest boats. To preview another
 * target, add its material or entity type to a document matcher.
 *
 * <p><b>Failure policy.</b> A document that fails to compile logs {@code previews/<name>.json:
 * <message>} and is skipped on first load; on a reload the previously compiled version stays live,
 * so a half-saved edit never blanks a preview.
 */
public final class PreviewDocumentRegistry {

  /** {@code match.special} marker for the viewer's own ender chest. */
  public static final String SPECIAL_ENDER_CHEST = "enderChest";
  /** {@code match.special} marker for the target-less "you may not open this" preview. */
  public static final String SPECIAL_LOCKED = "locked";
  /** {@code match.special} marker for the entity fallback; see the class javadoc. */
  public static final String SPECIAL_ANY_INVENTORY_HOLDER = "anyInventoryHolder";

  /**
   * The documents shipped in the jar. Hardcoded because listing a directory inside a jar is not
   * portable across class loaders; {@code ShippedPreviewDocumentTest} pins this list against the
   * resources actually present, so a document added to one and not the other fails the build.
   */
  static final List<String> SHIPPED = List.of(
      "beehive", "brewing_stand", "cauldron", "chest", "chiseled_bookshelf", "dispenser",
      "ender_chest", "furnace", "furnace_minecart", "hopper", "jukebox", "locked", "minecart", "shelf");

  private static final String FOLDER_NAME = "previews";
  private static final String EXTENSION = ".json";
  private static final String RESOURCE_ROOT = "/previews/";
  private static final String ALL = "*";

  private static final String WATCHDOG_ENTRY = "previews";

  /** Match strength within one priority; see the resolution order in the class javadoc. */
  private static final int GRADE_NONE = 0;
  private static final int GRADE_FALLBACK = 1;
  private static final int GRADE_GLOB = 2;
  private static final int GRADE_EXACT = 3;

  private final File folder;
  private final DocumentRegistry<CompiledPreviewDocument> registry;
  private final Set<String> warnedTies = ConcurrentHashMap.newKeySet();

  private volatile Snapshot snapshot = Snapshot.EMPTY;
  private boolean watching;

  /**
   * Extracts any shipped document missing from {@code previews/} and compiles every {@code *.json}
   * it finds. The folder itself is created by the first extracted document, never up front. Pure
   * file work — no scheduler, no Bukkit state — so the registry is constructible headlessly;
   * {@link #startWatching()} arms the hot reload separately.
   */
  public PreviewDocumentRegistry(File configDir) {
    this.folder = new File(configDir, FOLDER_NAME);
    this.registry = DocumentRegistry.folder(
        FOLDER_NAME,
        folder,
        PreviewDocumentParser::parse,
        ignored -> DocumentRegistry.UNVERSIONED
    );
    extract(ALL, false);
    reload();
  }

  // ---------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------

  /**
   * Recompiles every document in the folder and republishes the snapshot. Documents whose file has
   * gone are dropped; documents whose file no longer compiles keep the version already loaded.
   */
  public void reload() {
    registry.reload();
    publish(buildSnapshot(registry.snapshot()));
  }

  /**
   * Rebuilds the resolution order and the raycast eligibility set from scratch and publishes both
   * as one snapshot. Walking every material once per load is the price of an allocation-free
   * {@link #isPreviewBlockType}; it happens on boot and on edit, never per raycast.
   */
  private Snapshot buildSnapshot(Map<String, GlossDocument<CompiledPreviewDocument>> documents) {
    List<CompiledPreviewDocument> ordered = new ArrayList<>();
    for (GlossDocument<CompiledPreviewDocument> document : documents.values()) {
      ordered.add(document.value());
    }
    ordered.sort(Comparator.comparing(CompiledPreviewDocument::name));
    EnumSet<Material> blockTypes = EnumSet.noneOf(Material.class);
    for (Material material : Material.values()) {
      if (material == Material.AIR) {
        continue;
      }
      for (CompiledPreviewDocument document : ordered) {
        if (document.matchesBlock(material)) {
          blockTypes.add(material);
          break;
        }
      }
    }
    EnumSet<EntityType> entityTypes = EnumSet.noneOf(EntityType.class);
    boolean inventoryHolderFallback = false;
    for (CompiledPreviewDocument document : ordered) {
      if (SPECIAL_ANY_INVENTORY_HOLDER.equals(document.special())) {
        inventoryHolderFallback = true;
      }
      for (EntityType type : EntityType.values()) {
        if (explicitEntityGrade(document, type.name()) != GRADE_NONE) {
          entityTypes.add(type);
        }
      }
    }
    return new Snapshot(List.copyOf(ordered), blockTypes, entityTypes, inventoryHolderFallback);
  }

  private void publish(Snapshot replacement) {
    warnedTies.clear();
    snapshot = replacement;
  }

  // ---------------------------------------------------------------------
  // Extraction
  // ---------------------------------------------------------------------

  /**
   * Rewrites the named shipped document (or every one of them, for {@code "*"}) from the jar and
   * reloads, discarding local edits. Returns the names actually written, in shipped order; a name
   * that is not a shipped document writes nothing and returns empty.
   */
  public List<String> resetToDefault(String nameOrStar) {
    List<String> affected = extract(nameOrStar, true);
    if (!affected.isEmpty()) {
      reload();
      closeOpenPreviews();
    }
    return affected;
  }

  private List<String> extract(String nameOrStar, boolean overwrite) {
    String wanted = normalize(nameOrStar);
    List<String> written = new ArrayList<>();
    for (String name : SHIPPED) {
      if (!ALL.equals(wanted) && !name.equals(wanted)) {
        continue;
      }
      File file = new File(folder, name + EXTENSION);
      if (!overwrite && file.exists()) {
        continue;
      }
      try (InputStream stream = PreviewDocumentRegistry.class.getResourceAsStream(RESOURCE_ROOT + name + EXTENSION)) {
        if (stream == null) {
          Gloss.log(Level.WARNING, "previews/%s%s: missing from the jar, not extracted.", name, EXTENSION);
          continue;
        }
        byte[] content = stream.readAllBytes();
        AtomicFiles.createParentDirectories(file.toPath());
        Files.write(file.toPath(), content);
        written.add(name);
      } catch (IOException failure) {
        Gloss.logExceptionStack(false, failure, "previews/%s%s could not be extracted.", name, EXTENSION);
      }
    }
    return written;
  }

  // ---------------------------------------------------------------------
  // Hot reload
  // ---------------------------------------------------------------------

  /**
   * Arms the folder watcher as one {@link DataWatchdog} entry handling edits, creations, and
   * deletions together in a single pass.
   *
   * <p>The shared document registry owns native events, content reconciliation, deletion grace,
   * parse-failure retention, and watcher closure. The watchdog isolates a throwing entry itself,
   * so a broken document or a watcher-internal failure cannot kill hot reload.
   */
  public void startWatching() {
    if (watching) {
      return;
    }
    reload();
    watching = true;
    Gloss.instance.watchdog().register(WATCHDOG_ENTRY, this::watchTick);
  }

  public void stopWatching() {
    watching = false;
    registry.close();
    Gloss plugin = Gloss.instance;
    if (plugin != null && plugin.watchdog() != null) {
      plugin.watchdog().unregister(WATCHDOG_ENTRY);
    }
  }

  private void watchTick() {
    DocumentDelta delta = registry.poll();
    if (delta.isEmpty()) {
      return;
    }
    boolean scheduled = registry.prepareDispatch(delta,
        task -> SchedulerUtils.runGlobal(Gloss.instance, task), () -> {
          Snapshot replacement = buildSnapshot(registry.snapshot(delta));
          return () -> applyHotload(delta, replacement);
        });
    if (!scheduled) {
      Gloss.warnThrottled("preview-hotload-scheduling",
          "Preview hot reload could not reach the server thread; the change will be retried.");
    }
  }

  private void applyHotload(DocumentDelta delta, Snapshot replacement) {
    closeOpenPreviews();
    publish(replacement);
    for (String id : delta.loaded()) {
      Gloss.log(Level.INFO, "Preview document \"%s\" was hot loaded.", id);
    }
    for (String id : delta.removed()) {
      Gloss.log(Level.INFO, "Preview document \"%s\" was removed.", id);
    }
  }

  /**
   * Drops every open preview so the raycast loop rebuilds it from the snapshot just published. A
   * preview cannot be rebuilt in place — it holds the element list it was constructed from — and it
   * cannot be closed selectively either: an open preview does not record which document drew it,
   * and a priority change can move a target between documents anyway. Menus are left alone; a
   * document edit has nothing to do with them.
   */
  private static void closeOpenPreviews() {
    Gloss plugin = Gloss.instance;
    if (plugin == null) {
      return;
    }
    MenuSessionManager sessions = plugin.getSessionManager();
    if (sessions != null) {
      sessions.closeAllPreviews();
    }
  }

  // ---------------------------------------------------------------------
  // Resolution
  // ---------------------------------------------------------------------

  /** The document drawing this block material and the variables it matched with, or null. */
  public Resolved forBlock(Material material) {
    if (material == null || material == Material.AIR) {
      return null;
    }
    CompiledPreviewDocument best = best(snapshot.ordered(), material.name(), null);
    return best == null ? null : new Resolved(best, best.varsForBlock(material));
  }

  /** Entity counterpart of {@link #forBlock}; may resolve through the fallback document. */
  public Resolved forEntity(Entity entity) {
    if (entity == null) {
      return null;
    }
    CompiledPreviewDocument best = best(snapshot.ordered(), null, entity);
    return best == null ? null : new Resolved(best, best.varsForEntity(entity));
  }

  /**
   * The document carrying a {@code match.special} marker, e.g. {@link #SPECIAL_ENDER_CHEST} or
   * {@link #SPECIAL_LOCKED}. Its document-level {@code vars} come back unmerged: a special target
   * has no material or entity type for a variant to key off.
   */
  public Resolved special(String name) {
    if (name == null) {
      return null;
    }
    CompiledPreviewDocument best = null;
    for (CompiledPreviewDocument document : snapshot.ordered()) {
      if (!name.equals(document.special())) {
        continue;
      }
      if (best == null) {
        best = document;
      } else if (document.priority() > best.priority()) {
        best = document;
      } else if (document.priority() == best.priority()) {
        warnTie(best, document);
      }
    }
    return best == null ? null : new Resolved(best, best.vars());
  }

  /** Raycast hot path: one volatile read and an {@link EnumSet} ordinal test, no allocation. */
  public boolean isPreviewBlockType(Material material) {
    return material != null && material != Material.AIR && snapshot.blockTypes().contains(material);
  }

  public boolean hasEntityMatchers() {
    Snapshot current = snapshot;
    return !current.entityTypes().isEmpty() || current.inventoryHolderFallback();
  }

  /** Explicit entity matches plus the bounded inventory-cart and chest-boat fallback. */
  public boolean isPreviewEntity(Entity entity) {
    if (entity == null) {
      return false;
    }
    Snapshot current = snapshot;
    EntityType type = entity.getType();
    if (type != null && current.entityTypes().contains(type)) {
      return true;
    }
    return current.inventoryHolderFallback()
        && entity instanceof InventoryHolder
        && (entity instanceof Minecart || entity instanceof ChestBoat);
  }

  /** The base names of every loaded document, i.e. the file names without {@code .json}. */
  public Set<String> names() {
    return registry.ids();
  }

  /** The compiled document loaded under this name, or null when none is loaded; see {@link #names()}. */
  public CompiledPreviewDocument get(String name) {
    GlossDocument<CompiledPreviewDocument> document = name == null ? null : registry.get(name);
    return document == null ? null : document.value();
  }

  /**
   * Highest priority, then match strength, then document name. {@code ordered} is already sorted by
   * name, so the first document reaching a given (priority, grade) keeps it and every later tie is
   * a reportable ambiguity. Exactly one of {@code blockName} and {@code entity} is non-null.
   */
  private CompiledPreviewDocument best(List<CompiledPreviewDocument> ordered, String blockName, Entity entity) {
    CompiledPreviewDocument best = null;
    int bestGrade = GRADE_NONE;
    for (CompiledPreviewDocument document : ordered) {
      int grade = entity == null ? blockGrade(document, blockName) : entityGrade(document, entity);
      if (grade == GRADE_NONE) {
        continue;
      }
      if (best == null) {
        best = document;
        bestGrade = grade;
        continue;
      }
      int comparison = Integer.compare(document.priority(), best.priority());
      if (comparison == 0) {
        comparison = Integer.compare(grade, bestGrade);
      }
      if (comparison > 0) {
        best = document;
        bestGrade = grade;
      } else if (comparison == 0) {
        warnTie(best, document);
      }
    }
    return best;
  }

  private static int blockGrade(CompiledPreviewDocument document, String name) {
    int grade = grade(document.match().exactBlocks(), document.match().blockGlobs(), name);
    for (CompiledVariant variant : document.variants()) {
      if (grade == GRADE_EXACT) {
        break;
      }
      CompiledMatch match = variant.match();
      grade = Math.max(grade, grade(match.exactBlocks(), match.blockGlobs(), name));
    }
    return grade;
  }

  private static int entityGrade(CompiledPreviewDocument document, Entity entity) {
    EntityType type = entity.getType();
    if (type != null) {
      int grade = explicitEntityGrade(document, type.name());
      if (grade != GRADE_NONE) {
        return grade;
      }
    }
    boolean fallback = SPECIAL_ANY_INVENTORY_HOLDER.equals(document.special())
        && entity instanceof InventoryHolder
        && (entity instanceof Minecart || entity instanceof ChestBoat);
    return fallback ? GRADE_FALLBACK : GRADE_NONE;
  }

  private static int explicitEntityGrade(CompiledPreviewDocument document, String name) {
    int grade = grade(document.match().exactEntities(), document.match().entityGlobs(), name);
    for (CompiledVariant variant : document.variants()) {
      if (grade == GRADE_EXACT) {
        break;
      }
      CompiledMatch match = variant.match();
      grade = Math.max(grade, grade(match.exactEntities(), match.entityGlobs(), name));
    }
    return grade;
  }

  private static int grade(Set<String> exact, List<Predicate<String>> globs, String name) {
    if (exact.contains(name)) {
      return GRADE_EXACT;
    }
    for (Predicate<String> glob : globs) {
      if (glob.test(name)) {
        return GRADE_GLOB;
      }
    }
    return GRADE_NONE;
  }

  /** One line per pair of documents, reset on every reload so a reintroduced clash warns again. */
  private void warnTie(CompiledPreviewDocument winner, CompiledPreviewDocument loser) {
    if (!warnedTies.add(winner.name() + "|" + loser.name())) {
      return;
    }
    Gloss.log(Level.WARNING, "previews: %s and %s match the same targets at priority %d, using %s.",
        winner.name(), loser.name(), winner.priority(), winner.name());
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  /** The published resolution order and allocation-free block and entity raycast eligibility sets. */
  private record Snapshot(
      List<CompiledPreviewDocument> ordered,
      Set<Material> blockTypes,
      Set<EntityType> entityTypes,
      boolean inventoryHolderFallback
  ) {

    private static final Snapshot EMPTY = new Snapshot(List.of(), Set.of(), Set.of(), false);
  }

  /**
   * Strips a trailing {@code .json} so a user-supplied document name works whether or not the
   * caller typed the extension; {@code "*"} and any other name pass through unchanged. Public so
   * command handlers (e.g. {@code CommandGlossPreview.dump}) normalize a name arg the same way
   * {@link #resetToDefault} does before it reaches {@link #get}.
   */
  public static String normalize(String nameOrStar) {
    if (nameOrStar == null) {
      return "";
    }
    return nameOrStar.endsWith(EXTENSION)
        ? nameOrStar.substring(0, nameOrStar.length() - EXTENSION.length())
        : nameOrStar;
  }

  /**
   * A {@link PreviewDocumentException} already carries its own document name, which the log prefix
   * supplies; stripping it keeps the line readable rather than naming the file twice.
   */
  private static String detail(String name, Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.isEmpty()) {
      return failure.getClass().getSimpleName();
    }
    String prefix = name + EXTENSION + " ";
    return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
  }
}
