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
 * touches — the board table, and the one world/bucket a placement moves between — and shares every
 * untouched bucket with the previous state.
 *
 * <p>{@link #generation()} increments on every published change so callers can cache a query result
 * and re-run it only when the index actually moved.
 */
public final class PanelSpatialIndex {
  private static final double CHUNK_SIZE = 16.0D;

  private final Object writeLock = new Object();

  private volatile State state = State.EMPTY;

  public int size() {
    return state.boardsByUuid().size();
  }

  public boolean isEmpty() {
    return state.boardsByUuid().isEmpty();
  }

  /** Increments on every published change; equal generations mean an identical index. */
  public long generation() {
    return state.generation();
  }

  public Optional<PanelDefinition> get(String id) {
    State current = state;
    UUID boardUuid = current.uuidsById().get(PanelIds.canonicalize(id));
    return boardUuid == null ? Optional.empty() : Optional.ofNullable(current.boardsByUuid().get(boardUuid));
  }

  public Optional<PanelDefinition> get(UUID boardUuid) {
    return Optional.ofNullable(state.boardsByUuid().get(Objects.requireNonNull(boardUuid, "boardUuid")));
  }

  public List<PanelDefinition> list() {
    return state.ordered();
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

    synchronized (writeLock) {
      state = state.publish(replacementBoards, replacementIds, replacementChunks);
    }
  }

  public void upsert(PanelDefinition board) {
    PanelDefinition requiredBoard = Objects.requireNonNull(board, "board");
    synchronized (writeLock) {
      State current = state;
      UUID idOwner = current.uuidsById().get(requiredBoard.id());
      if (idOwner != null && !idOwner.equals(requiredBoard.uuid())) {
        throw new IllegalArgumentException("duplicate panel ID: " + requiredBoard.id());
      }
      PanelDefinition previous = current.boardsByUuid().get(requiredBoard.uuid());
      if (requiredBoard.equals(previous)) {
        // Re-publishing an identical definition would produce an identical state; skipping keeps
        // a stationary follow-panel from invalidating every viewer's cached query every tick.
        return;
      }

      Map<UUID, PanelDefinition> boardsByUuid = new HashMap<>(current.boardsByUuid());
      boardsByUuid.put(requiredBoard.uuid(), requiredBoard);
      Map<String, UUID> uuidsById = current.uuidsById();
      if (previous == null || !previous.id().equals(requiredBoard.id())) {
        uuidsById = new HashMap<>(uuidsById);
        if (previous != null) {
          uuidsById.remove(previous.id());
        }
        uuidsById.put(requiredBoard.id(), requiredBoard.uuid());
      }
      Map<UUID, Map<Long, Set<UUID>>> chunksByWorld = current.chunksByWorld();
      if (previous == null || !sameBucket(previous, requiredBoard)) {
        chunksByWorld = withBoard(withoutBoard(chunksByWorld, previous), requiredBoard);
      }
      state = current.publish(boardsByUuid, uuidsById, chunksByWorld);
    }
  }

  public boolean remove(UUID boardUuid) {
    UUID requiredUuid = Objects.requireNonNull(boardUuid, "boardUuid");
    synchronized (writeLock) {
      State current = state;
      PanelDefinition removed = current.boardsByUuid().get(requiredUuid);
      if (removed == null) {
        return false;
      }
      Map<UUID, PanelDefinition> boardsByUuid = new HashMap<>(current.boardsByUuid());
      boardsByUuid.remove(requiredUuid);
      Map<String, UUID> uuidsById = new HashMap<>(current.uuidsById());
      uuidsById.remove(removed.id());
      state = current.publish(boardsByUuid, uuidsById, withoutBoard(current.chunksByWorld(), removed));
      return true;
    }
  }

  public List<PanelDefinition> query(UUID worldUuid, double x, double z, double radius) {
    UUID requiredWorldUuid = Objects.requireNonNull(worldUuid, "worldUuid");
    requireFinite(x, "x");
    requireFinite(z, "z");
    requireRadius(radius);

    State current = state;
    Map<Long, Set<UUID>> worldChunks = current.chunksByWorld().get(requiredWorldUuid);
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
      PanelDefinition board = current.boardsByUuid().get(candidateUuid);
      if (board != null && horizontalDistance(board, x, z) <= radius) {
        matches.add(board);
      }
    }
    matches.sort((left, right) -> compare(left, right, x, z));
    return List.copyOf(matches);
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

  /** Copies the one world map and bucket the placement lands in; every other bucket is shared. */
  private static Map<UUID, Map<Long, Set<UUID>>> withBoard(Map<UUID, Map<Long, Set<UUID>>> chunks,
                                                           PanelDefinition board) {
    PanelTransform transform = board.transform();
    long key = chunkKey(transform.x(), transform.z());
    Map<UUID, Map<Long, Set<UUID>>> replacement = new HashMap<>(chunks);
    Map<Long, Set<UUID>> worldChunks = new HashMap<>(replacement.getOrDefault(transform.worldUuid(), Map.of()));
    Set<UUID> bucket = new HashSet<>(worldChunks.getOrDefault(key, Set.of()));
    bucket.add(board.uuid());
    worldChunks.put(key, bucket);
    replacement.put(transform.worldUuid(), worldChunks);
    return replacement;
  }

  /** The mirror of {@link #withBoard}; a null board (nothing was indexed yet) is a no-op. */
  private static Map<UUID, Map<Long, Set<UUID>>> withoutBoard(Map<UUID, Map<Long, Set<UUID>>> chunks,
                                                              PanelDefinition board) {
    if (board == null) {
      return chunks;
    }
    PanelTransform transform = board.transform();
    Map<Long, Set<UUID>> worldChunks = chunks.get(transform.worldUuid());
    if (worldChunks == null) {
      return chunks;
    }
    long key = chunkKey(transform.x(), transform.z());
    Set<UUID> bucket = worldChunks.get(key);
    if (bucket == null || !bucket.contains(board.uuid())) {
      return chunks;
    }
    Map<UUID, Map<Long, Set<UUID>>> replacement = new HashMap<>(chunks);
    Map<Long, Set<UUID>> replacementChunks = new HashMap<>(worldChunks);
    if (bucket.size() == 1) {
      replacementChunks.remove(key);
    } else {
      Set<UUID> replacementBucket = new HashSet<>(bucket);
      replacementBucket.remove(board.uuid());
      replacementChunks.put(key, replacementBucket);
    }
    if (replacementChunks.isEmpty()) {
      replacement.remove(transform.worldUuid());
    } else {
      replacement.put(transform.worldUuid(), replacementChunks);
    }
    return replacement;
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

  /**
   * One published index. Every map inside is effectively immutable: writers copy what they change
   * and share what they do not, so a state that has been published is never edited again.
   */
  private record State(Map<UUID, PanelDefinition> boardsByUuid,
                       Map<String, UUID> uuidsById,
                       Map<UUID, Map<Long, Set<UUID>>> chunksByWorld,
                       List<PanelDefinition> ordered,
                       long generation) {

    private static final State EMPTY = new State(Map.of(), Map.of(), Map.of(), List.of(), 0L);

    private State publish(Map<UUID, PanelDefinition> boards, Map<String, UUID> ids,
                          Map<UUID, Map<Long, Set<UUID>>> chunks) {
      List<PanelDefinition> sorted = new ArrayList<>(boards.values());
      sorted.sort((left, right) -> left.id().compareTo(right.id()));
      return new State(boards, ids, chunks, List.copyOf(sorted), generation + 1L);
    }
  }
}
