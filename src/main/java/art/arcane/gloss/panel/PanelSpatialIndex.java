package art.arcane.gloss.panel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Placement index for persistent panels: identity lookups by id/uuid plus a chunk-bucketed
 * horizontal range query.
 *
 * <p><b>Snapshot publication.</b> Every read answers from one immutable {@link State} behind a
 * single volatile field — the same shape {@code PreviewDocumentRegistry} uses — so a viewer query
 * takes no lock at all. The runtime queries this once per viewer per tick, which at a full server
 * is thousands of reads per second against a structure that changes only when an operator edits a
 * panel or a follow-panel's target moves; a reader-writer lock there is pure contention for no
 * benefit. Writers serialize on {@link #writeLock}, rebuild the affected maps, and publish the new
 * state in one assignment: a failed validation therefore leaves the previous state untouched, and
 * a reader never observes a half-applied mutation.
 *
 * <p>Published maps and buckets are never mutated afterwards. A write copies exactly what it
 * touches — the board table and the affected world/buckets — and shares every untouched bucket
 * with the previous state.
 *
 * <p>{@link #generation()} increments on every published change. A bounded change history lets
 * callers retain a cached query when every intervening change occurred outside its chunk window.
 */
public final class PanelSpatialIndex {
  private static final double CHUNK_SIZE = 16.0D;
  private static final int DEFINITION_PARTITIONS = 64;
  static final int CHANGE_HISTORY_SIZE = 1024;

  private final Object writeLock = new Object();
  private final AtomicReferenceArray<SpatialChange> changeHistory =
      new AtomicReferenceArray<>(CHANGE_HISTORY_SIZE);

  private volatile State state = State.EMPTY;

  public int size() {
    return state.boards().size();
  }

  public boolean isEmpty() {
    return state.boards().isEmpty();
  }

  /** Increments on every published change; equal generations mean an identical index. */
  public long generation() {
    return state.generation();
  }

  public Optional<PanelDefinition> get(String id) {
    State current = state;
    UUID boardUuid = current.uuidsById().get(PanelIds.canonicalize(id));
    return boardUuid == null ? Optional.empty() : Optional.ofNullable(current.boards().get(boardUuid));
  }

  public Optional<PanelDefinition> get(UUID boardUuid) {
    return Optional.ofNullable(state.boards().get(Objects.requireNonNull(boardUuid, "boardUuid")));
  }

  public List<PanelDefinition> list() {
    State current = state;
    List<PanelDefinition> ordered = new ArrayList<>(current.orderedUuids().size());
    for (UUID boardUuid : current.orderedUuids()) {
      PanelDefinition board = current.boards().get(boardUuid);
      if (board != null) {
        ordered.add(board);
      }
    }
    return List.copyOf(ordered);
  }

  public void replaceAll(Collection<PanelDefinition> boards) {
    Objects.requireNonNull(boards, "boards");
    Map<UUID, PanelDefinition> replacementBoards = new HashMap<>(capacityFor(boards.size()));
    Map<String, UUID> replacementIds = new HashMap<>(capacityFor(boards.size()));
    Map<UUID, Map<Long, Set<UUID>>> replacementChunks = new HashMap<>();

    for (PanelDefinition board : boards) {
      PanelDefinition requiredBoard = Objects.requireNonNull(board, "boards must not contain null");
      if (replacementBoards.putIfAbsent(requiredBoard.uuid(), requiredBoard) != null) {
        throw new IllegalArgumentException("duplicate panel UUID: " + requiredBoard.uuid());
      }
      UUID previousIdOwner = replacementIds.putIfAbsent(requiredBoard.id(), requiredBoard.uuid());
      if (previousIdOwner != null) {
        throw new IllegalArgumentException("duplicate panel ID: " + requiredBoard.id());
      }
      addToChunk(replacementChunks, requiredBoard);
    }

    List<UUID> orderedUuids = orderedUuids(replacementBoards.values());
    DefinitionTable replacementTable = DefinitionTable.from(replacementBoards);
    synchronized (writeLock) {
      State next = state.publish(replacementTable, replacementIds, replacementChunks, orderedUuids);
      publish(next, SpatialChange.global(next.generation()));
    }
  }

  public void upsert(PanelDefinition board) {
    upsertAll(List.of(Objects.requireNonNull(board, "board")));
  }

  public void upsertAll(Collection<PanelDefinition> boards) {
    Objects.requireNonNull(boards, "boards");
    if (boards.isEmpty()) {
      return;
    }
    Map<UUID, PanelDefinition> replacements = new HashMap<>(capacityFor(boards.size()));
    for (PanelDefinition board : boards) {
      PanelDefinition requiredBoard = Objects.requireNonNull(board, "boards must not contain null");
      if (replacements.putIfAbsent(requiredBoard.uuid(), requiredBoard) != null) {
        throw new IllegalArgumentException("duplicate panel UUID: " + requiredBoard.uuid());
      }
    }

    synchronized (writeLock) {
      State current = state;
      Map<UUID, PanelDefinition> changed = new HashMap<>(capacityFor(replacements.size()));
      boolean identifiersChanged = false;
      for (PanelDefinition replacement : replacements.values()) {
        PanelDefinition previous = current.boards().get(replacement.uuid());
        if (replacement.equals(previous)) {
          continue;
        }
        changed.put(replacement.uuid(), replacement);
        identifiersChanged |= previous == null || !previous.id().equals(replacement.id());
      }
      if (changed.isEmpty()) {
        return;
      }

      DefinitionTable updatedBoards = current.boards().withAll(changed);
      Map<String, UUID> uuidsById = current.uuidsById();
      List<UUID> orderedUuids = current.orderedUuids();
      if (identifiersChanged) {
        uuidsById = new HashMap<>(current.uuidsById());
        for (PanelDefinition replacement : changed.values()) {
          PanelDefinition previous = current.boards().get(replacement.uuid());
          if (previous != null) {
            uuidsById.remove(previous.id());
          }
        }
        for (PanelDefinition replacement : changed.values()) {
          UUID previousOwner = uuidsById.putIfAbsent(replacement.id(), replacement.uuid());
          if (previousOwner != null) {
            throw new IllegalArgumentException("duplicate panel ID: " + replacement.id());
          }
        }
        orderedUuids = orderedUuids(updatedBoards.values());
      }

      ChunkMutation chunkMutation = null;
      Map<UUID, Set<Long>> touchedBuckets = new HashMap<>();
      for (PanelDefinition replacement : changed.values()) {
        PanelDefinition previous = current.boards().get(replacement.uuid());
        touch(touchedBuckets, previous);
        touch(touchedBuckets, replacement);
        if (previous == null || !sameBucket(previous, replacement)) {
          if (chunkMutation == null) {
            chunkMutation = new ChunkMutation(current.chunksByWorld());
          }
          if (previous != null) {
            chunkMutation.remove(previous);
          }
          chunkMutation.add(replacement);
        }
      }
      Map<UUID, Map<Long, Set<UUID>>> chunksByWorld = chunkMutation == null
          ? current.chunksByWorld()
          : chunkMutation.finish();
      State next = current.publish(updatedBoards, uuidsById, chunksByWorld, orderedUuids);
      publish(next, SpatialChange.local(next.generation(), touchedBuckets));
    }
  }

  public boolean remove(UUID boardUuid) {
    UUID requiredUuid = Objects.requireNonNull(boardUuid, "boardUuid");
    synchronized (writeLock) {
      State current = state;
      PanelDefinition removed = current.boards().get(requiredUuid);
      if (removed == null) {
        return false;
      }
      DefinitionTable boards = current.boards().without(requiredUuid);
      Map<String, UUID> uuidsById = new HashMap<>(current.uuidsById());
      uuidsById.remove(removed.id());
      ChunkMutation chunkMutation = new ChunkMutation(current.chunksByWorld());
      chunkMutation.remove(removed);
      Map<UUID, Set<Long>> touchedBuckets = new HashMap<>();
      touch(touchedBuckets, removed);
      State next = current.publish(boards, uuidsById, chunkMutation.finish(),
          orderedUuids(boards.values()));
      publish(next, SpatialChange.local(next.generation(), touchedBuckets));
      return true;
    }
  }

  public List<PanelDefinition> query(UUID worldUuid, double x, double z, double radius) {
    return querySnapshot(worldUuid, x, z, radius).boards();
  }

  QuerySnapshot querySnapshot(UUID worldUuid, double x, double z, double radius) {
    UUID requiredWorldUuid = Objects.requireNonNull(worldUuid, "worldUuid");
    requireFinite(x, "x");
    requireFinite(z, "z");
    requireRadius(radius);

    State current = state;
    return new QuerySnapshot(query(current, requiredWorldUuid, x, z, radius), current.generation());
  }

  boolean changedSince(long previousGeneration, long endingGeneration,
                       UUID worldUuid, double x, double z, double radius) {
    UUID requiredWorldUuid = Objects.requireNonNull(worldUuid, "worldUuid");
    requireFinite(x, "x");
    requireFinite(z, "z");
    requireRadius(radius);
    if (previousGeneration < 0L || endingGeneration < previousGeneration
        || endingGeneration > state.generation()
        || endingGeneration - previousGeneration > CHANGE_HISTORY_SIZE) {
      return true;
    }
    if (previousGeneration == endingGeneration) {
      return false;
    }
    int minimumChunkX = chunkCoordinate(x - radius);
    int maximumChunkX = chunkCoordinate(x + radius);
    int minimumChunkZ = chunkCoordinate(z - radius);
    int maximumChunkZ = chunkCoordinate(z + radius);
    for (long generation = previousGeneration + 1L; generation <= endingGeneration; generation++) {
      SpatialChange change = changeHistory.get(historyIndex(generation));
      if (change == null || change.generation() != generation || change.global()
          || change.touches(requiredWorldUuid, minimumChunkX, maximumChunkX,
          minimumChunkZ, maximumChunkZ)) {
        return true;
      }
    }
    return false;
  }

  private static List<PanelDefinition> query(State current, UUID worldUuid,
                                             double x, double z, double radius) {
    Map<Long, Set<UUID>> worldChunks = current.chunksByWorld().get(worldUuid);
    if (worldChunks == null || worldChunks.isEmpty()) {
      return List.of();
    }

    Set<UUID> candidateUuids = collectCandidates(worldChunks,
        chunkCoordinate(x - radius), chunkCoordinate(x + radius),
        chunkCoordinate(z - radius), chunkCoordinate(z + radius));
    if (candidateUuids.isEmpty()) {
      return List.of();
    }
    List<PanelDefinition> matches = new ArrayList<>(candidateUuids.size());
    for (UUID candidateUuid : candidateUuids) {
      PanelDefinition board = current.boards().get(candidateUuid);
      if (board != null && horizontalDistance(board, x, z) <= radius) {
        matches.add(board);
      }
    }
    matches.sort((left, right) -> compare(left, right, x, z));
    return List.copyOf(matches);
  }

  public boolean hasCandidate(UUID worldUuid, double x, double z, double radius) {
    UUID requiredWorldUuid = Objects.requireNonNull(worldUuid, "worldUuid");
    requireFinite(x, "x");
    requireFinite(z, "z");
    requireRadius(radius);

    State current = state;
    Map<Long, Set<UUID>> worldChunks = current.chunksByWorld().get(requiredWorldUuid);
    if (worldChunks == null || worldChunks.isEmpty()) {
      return false;
    }

    int minimumChunkX = chunkCoordinate(x - radius);
    int maximumChunkX = chunkCoordinate(x + radius);
    int minimumChunkZ = chunkCoordinate(z - radius);
    int maximumChunkZ = chunkCoordinate(z + radius);
    long width = (long) maximumChunkX - minimumChunkX + 1L;
    long depth = (long) maximumChunkZ - minimumChunkZ + 1L;
    if (width <= worldChunks.size() && depth <= worldChunks.size()
        && width * depth <= worldChunks.size()) {
      for (long chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
        for (long chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
          Set<UUID> bucket = worldChunks.get(chunkKey((int) chunkX, (int) chunkZ));
          if (bucket != null && containsCandidate(current, bucket, x, z, radius)) {
            return true;
          }
        }
      }
      return false;
    }

    for (Map.Entry<Long, Set<UUID>> entry : worldChunks.entrySet()) {
      long key = entry.getKey();
      int chunkX = (int) (key >> 32);
      int chunkZ = (int) key;
      if (chunkX >= minimumChunkX && chunkX <= maximumChunkX
          && chunkZ >= minimumChunkZ && chunkZ <= maximumChunkZ
          && containsCandidate(current, entry.getValue(), x, z, radius)) {
        return true;
      }
    }
    return false;
  }

  private static int capacityFor(int size) {
    if (size < 3) {
      return size + 1;
    }
    return (int) Math.min(Integer.MAX_VALUE, (long) Math.ceil(size / 0.75D));
  }

  /** True when both placements live in the same world chunk, so no bucket has to move. */
  private static boolean sameBucket(PanelDefinition previous, PanelDefinition board) {
    PanelTransform previousTransform = previous.transform();
    PanelTransform boardTransform = board.transform();
    return previousTransform.worldUuid().equals(boardTransform.worldUuid())
        && chunkKey(previousTransform.x(), previousTransform.z())
        == chunkKey(boardTransform.x(), boardTransform.z());
  }

  private static void addToChunk(Map<UUID, Map<Long, Set<UUID>>> chunks, PanelDefinition board) {
    PanelTransform transform = board.transform();
    chunks.computeIfAbsent(transform.worldUuid(), ignored -> new HashMap<>())
        .computeIfAbsent(chunkKey(transform.x(), transform.z()), ignored -> new HashSet<>())
        .add(board.uuid());
  }

  private void publish(State next, SpatialChange change) {
    changeHistory.set(historyIndex(next.generation()), change);
    state = next;
  }

  private static int historyIndex(long generation) {
    return (int) Math.floorMod(generation, CHANGE_HISTORY_SIZE);
  }

  private static List<UUID> orderedUuids(Collection<PanelDefinition> boards) {
    List<PanelDefinition> ordered = new ArrayList<>(boards);
    ordered.sort((left, right) -> left.id().compareTo(right.id()));
    List<UUID> uuids = new ArrayList<>(ordered.size());
    for (PanelDefinition board : ordered) {
      uuids.add(board.uuid());
    }
    return List.copyOf(uuids);
  }

  private static void touch(Map<UUID, Set<Long>> touchedBuckets, PanelDefinition board) {
    if (board == null) {
      return;
    }
    PanelTransform transform = board.transform();
    touchedBuckets.computeIfAbsent(transform.worldUuid(), ignored -> new HashSet<>())
        .add(chunkKey(transform.x(), transform.z()));
  }

  private static final class ChunkMutation {
    private final Map<UUID, Map<Long, Set<UUID>>> chunksByWorld;
    private final Map<UUID, Map<Long, Set<UUID>>> copiedWorlds = new HashMap<>();
    private final Map<WorldBucket, Set<UUID>> copiedBuckets = new HashMap<>();

    private ChunkMutation(Map<UUID, Map<Long, Set<UUID>>> source) {
      this.chunksByWorld = new HashMap<>(source);
    }

    private void add(PanelDefinition board) {
      PanelTransform transform = board.transform();
      bucket(transform.worldUuid(), chunkKey(transform.x(), transform.z())).add(board.uuid());
    }

    private void remove(PanelDefinition board) {
      PanelTransform transform = board.transform();
      bucket(transform.worldUuid(), chunkKey(transform.x(), transform.z())).remove(board.uuid());
    }

    private Set<UUID> bucket(UUID worldUuid, long chunkKey) {
      WorldBucket worldBucket = new WorldBucket(worldUuid, chunkKey);
      Set<UUID> existing = copiedBuckets.get(worldBucket);
      if (existing != null) {
        return existing;
      }
      Map<Long, Set<UUID>> worldChunks = copiedWorlds.computeIfAbsent(worldUuid, ignored -> {
        Map<Long, Set<UUID>> replacement = new HashMap<>(
            chunksByWorld.getOrDefault(worldUuid, Map.of()));
        chunksByWorld.put(worldUuid, replacement);
        return replacement;
      });
      Set<UUID> replacement = new HashSet<>(worldChunks.getOrDefault(chunkKey, Set.of()));
      worldChunks.put(chunkKey, replacement);
      copiedBuckets.put(worldBucket, replacement);
      return replacement;
    }

    private Map<UUID, Map<Long, Set<UUID>>> finish() {
      for (Map.Entry<WorldBucket, Set<UUID>> entry : copiedBuckets.entrySet()) {
        if (entry.getValue().isEmpty()) {
          copiedWorlds.get(entry.getKey().worldUuid()).remove(entry.getKey().chunkKey());
        }
      }
      for (Map.Entry<UUID, Map<Long, Set<UUID>>> entry : copiedWorlds.entrySet()) {
        if (entry.getValue().isEmpty()) {
          chunksByWorld.remove(entry.getKey());
        }
      }
      return chunksByWorld;
    }
  }

  /**
   * Walks the requested chunk window when it is smaller than the world's occupied bucket count,
   * and the occupied buckets themselves otherwise. Both paths select the same buckets; the choice
   * only decides which of the two is cheaper for the radius asked for.
   */
  private static Set<UUID> collectCandidates(Map<Long, Set<UUID>> worldChunks,
                                             int minimumChunkX, int maximumChunkX,
                                             int minimumChunkZ, int maximumChunkZ) {
    long width = (long) maximumChunkX - minimumChunkX + 1L;
    long depth = (long) maximumChunkZ - minimumChunkZ + 1L;
    if (width > worldChunks.size() || depth > worldChunks.size()
        || width * depth > worldChunks.size()) {
      return collectExistingBuckets(worldChunks, minimumChunkX, maximumChunkX, minimumChunkZ, maximumChunkZ);
    }

    Set<UUID> candidates = new HashSet<>();
    for (long chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
      for (long chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
        Set<UUID> bucket = worldChunks.get(chunkKey((int) chunkX, (int) chunkZ));
        if (bucket != null) {
          candidates.addAll(bucket);
        }
      }
    }
    return candidates;
  }

  private static Set<UUID> collectExistingBuckets(Map<Long, Set<UUID>> worldChunks,
                                                  int minimumChunkX, int maximumChunkX,
                                                  int minimumChunkZ, int maximumChunkZ) {
    Set<UUID> candidates = new HashSet<>();
    for (Map.Entry<Long, Set<UUID>> entry : worldChunks.entrySet()) {
      long key = entry.getKey();
      int chunkX = (int) (key >> 32);
      int chunkZ = (int) key;
      if (chunkX >= minimumChunkX && chunkX <= maximumChunkX
          && chunkZ >= minimumChunkZ && chunkZ <= maximumChunkZ) {
        candidates.addAll(entry.getValue());
      }
    }
    return candidates;
  }

  private static int compare(PanelDefinition left, PanelDefinition right, double x, double z) {
    int distanceComparison = Double.compare(horizontalDistance(left, x, z), horizontalDistance(right, x, z));
    if (distanceComparison != 0) {
      return distanceComparison;
    }
    int idComparison = left.id().compareTo(right.id());
    if (idComparison != 0) {
      return idComparison;
    }
    return left.uuid().compareTo(right.uuid());
  }

  private static boolean containsCandidate(State state, Set<UUID> candidates,
                                           double x, double z, double radius) {
    for (UUID candidateUuid : candidates) {
      PanelDefinition board = state.boards().get(candidateUuid);
      if (board != null && horizontalDistance(board, x, z) <= radius) {
        return true;
      }
    }
    return false;
  }

  private static double horizontalDistance(PanelDefinition board, double x, double z) {
    PanelTransform transform = board.transform();
    return Math.hypot(transform.x() - x, transform.z() - z);
  }

  /** Both chunk coordinates packed into one key, so a bucket lookup allocates nothing. */
  private static long chunkKey(double x, double z) {
    return chunkKey(chunkCoordinate(x), chunkCoordinate(z));
  }

  private static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }

  private static int chunkCoordinate(double blockCoordinate) {
    if (blockCoordinate <= (double) Integer.MIN_VALUE * CHUNK_SIZE) {
      return Integer.MIN_VALUE;
    }
    if (blockCoordinate >= (double) Integer.MAX_VALUE * CHUNK_SIZE) {
      return Integer.MAX_VALUE;
    }
    return (int) Math.floor(blockCoordinate / CHUNK_SIZE);
  }

  private static void requireFinite(double value, String field) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(field + " must be finite");
    }
  }

  private static void requireRadius(double radius) {
    if (!Double.isFinite(radius) || radius < 0.0D) {
      throw new IllegalArgumentException("radius must be finite and non-negative");
    }
  }

  record QuerySnapshot(List<PanelDefinition> boards, long generation) {
  }

  private record WorldBucket(UUID worldUuid, long chunkKey) {
  }

  private record SpatialChange(long generation, boolean global,
                               Map<UUID, Set<Long>> touchedBuckets) {
    private static SpatialChange global(long generation) {
      return new SpatialChange(generation, true, Map.of());
    }

    private static SpatialChange local(long generation, Map<UUID, Set<Long>> touchedBuckets) {
      Map<UUID, Set<Long>> frozen = new HashMap<>(capacityFor(touchedBuckets.size()));
      for (Map.Entry<UUID, Set<Long>> entry : touchedBuckets.entrySet()) {
        frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
      }
      return new SpatialChange(generation, false, Map.copyOf(frozen));
    }

    private boolean touches(UUID worldUuid, int minimumChunkX, int maximumChunkX,
                            int minimumChunkZ, int maximumChunkZ) {
      Set<Long> worldBuckets = touchedBuckets.get(worldUuid);
      if (worldBuckets == null) {
        return false;
      }
      long width = (long) maximumChunkX - minimumChunkX + 1L;
      long depth = (long) maximumChunkZ - minimumChunkZ + 1L;
      if (width <= worldBuckets.size() && depth <= worldBuckets.size()
          && width * depth <= worldBuckets.size()) {
        for (long chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
          for (long chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
            if (worldBuckets.contains(chunkKey((int) chunkX, (int) chunkZ))) {
              return true;
            }
          }
        }
        return false;
      }
      for (long chunkKey : worldBuckets) {
        int chunkX = (int) (chunkKey >> 32);
        int chunkZ = (int) chunkKey;
        if (chunkX >= minimumChunkX && chunkX <= maximumChunkX
            && chunkZ >= minimumChunkZ && chunkZ <= maximumChunkZ) {
          return true;
        }
      }
      return false;
    }
  }

  private record DefinitionTable(List<Map<UUID, PanelDefinition>> partitions, int size) {
    private static final DefinitionTable EMPTY = empty();

    private static DefinitionTable empty() {
      List<Map<UUID, PanelDefinition>> partitions = new ArrayList<>(DEFINITION_PARTITIONS);
      for (int index = 0; index < DEFINITION_PARTITIONS; index++) {
        partitions.add(Map.of());
      }
      return new DefinitionTable(List.copyOf(partitions), 0);
    }

    private static DefinitionTable from(Map<UUID, PanelDefinition> boards) {
      if (boards.isEmpty()) {
        return EMPTY;
      }
      List<Map<UUID, PanelDefinition>> partitions = new ArrayList<>(DEFINITION_PARTITIONS);
      for (int index = 0; index < DEFINITION_PARTITIONS; index++) {
        partitions.add(new HashMap<>());
      }
      for (PanelDefinition board : boards.values()) {
        partitions.get(partition(board.uuid())).put(board.uuid(), board);
      }
      return new DefinitionTable(List.copyOf(partitions), boards.size());
    }

    private PanelDefinition get(UUID boardUuid) {
      return partitions.get(partition(boardUuid)).get(boardUuid);
    }

    private boolean isEmpty() {
      return size == 0;
    }

    private DefinitionTable withAll(Map<UUID, PanelDefinition> changed) {
      List<Map<UUID, PanelDefinition>> replacements = new ArrayList<>(partitions);
      Map<Integer, Map<UUID, PanelDefinition>> copied = new HashMap<>();
      int replacementSize = size;
      for (PanelDefinition board : changed.values()) {
        int partition = partition(board.uuid());
        Map<UUID, PanelDefinition> replacement = copied.get(partition);
        if (replacement == null) {
          replacement = new HashMap<>(partitions.get(partition));
          copied.put(partition, replacement);
          replacements.set(partition, replacement);
        }
        if (replacement.put(board.uuid(), board) == null) {
          replacementSize++;
        }
      }
      return new DefinitionTable(List.copyOf(replacements), replacementSize);
    }

    private DefinitionTable without(UUID boardUuid) {
      int partition = partition(boardUuid);
      Map<UUID, PanelDefinition> source = partitions.get(partition);
      if (!source.containsKey(boardUuid)) {
        return this;
      }
      List<Map<UUID, PanelDefinition>> replacements = new ArrayList<>(partitions);
      Map<UUID, PanelDefinition> replacement = new HashMap<>(source);
      replacement.remove(boardUuid);
      replacements.set(partition, replacement);
      return new DefinitionTable(List.copyOf(replacements), size - 1);
    }

    private List<PanelDefinition> values() {
      List<PanelDefinition> values = new ArrayList<>(size);
      for (Map<UUID, PanelDefinition> partition : partitions) {
        values.addAll(partition.values());
      }
      return values;
    }

    private static int partition(UUID boardUuid) {
      return Math.floorMod(boardUuid.hashCode(), DEFINITION_PARTITIONS);
    }
  }

  /**
   * One published index. Every table, map, and bucket is effectively immutable: writers copy what
   * they change and share what they do not, so a published state is never edited again.
   */
  private record State(DefinitionTable boards,
                       Map<String, UUID> uuidsById,
                       Map<UUID, Map<Long, Set<UUID>>> chunksByWorld,
                       List<UUID> orderedUuids,
                       long generation) {

    private static final State EMPTY = new State(DefinitionTable.EMPTY, Map.of(), Map.of(), List.of(), 0L);

    private State publish(DefinitionTable boards, Map<String, UUID> ids,
                          Map<UUID, Map<Long, Set<UUID>>> chunks, List<UUID> orderedUuids) {
      return new State(boards, ids, chunks, orderedUuids, generation + 1L);
    }
  }
}
