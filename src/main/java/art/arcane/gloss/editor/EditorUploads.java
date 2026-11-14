package art.arcane.gloss.editor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * Image bytes uploaded with {@code POST /v1/images}, held in memory until a publish names their hash: at most
 * {@link #LIFETIME} each and {@code maxProjectMiB} in all, oldest dropped first.
 */
final class EditorUploads {
  static final Duration LIFETIME = Duration.ofMinutes(10);

  record Staged(String hash, String mediaType, byte[] bytes, Instant expires) {
  }

  private final Clock clock;
  private final IntSupplier maximumBytes;
  private final LinkedHashMap<String, Staged> staged = new LinkedHashMap<>();
  private long bytes;

  EditorUploads(Clock clock, IntSupplier maximumBytes) {
    this.clock = Objects.requireNonNull(clock);
    this.maximumBytes = Objects.requireNonNull(maximumBytes);
  }

  /** Stages the bytes; false when the same hash was staged already (its lifetime starts again). */
  synchronized boolean put(String hash, String mediaType, byte[] content) {
    prune();
    Staged previous = staged.remove(hash);
    if (previous != null) {
      bytes -= previous.bytes().length;
    }
    staged.put(hash, new Staged(hash, mediaType, content.clone(), clock.instant().plus(LIFETIME)));
    bytes += content.length;
    Iterator<Map.Entry<String, Staged>> oldest = staged.entrySet().iterator();
    while (bytes > maximumBytes.getAsInt() && oldest.hasNext()) {
      Map.Entry<String, Staged> entry = oldest.next();
      if (entry.getKey().equals(hash)) {
        continue;
      }
      bytes -= entry.getValue().bytes().length;
      oldest.remove();
    }
    return previous == null;
  }

  synchronized Optional<Staged> get(String hash) {
    prune();
    return Optional.ofNullable(staged.get(hash));
  }

  synchronized int size() {
    prune();
    return staged.size();
  }

  private void prune() {
    Instant now = clock.instant();
    Iterator<Map.Entry<String, Staged>> entries = staged.entrySet().iterator();
    while (entries.hasNext()) {
      Staged entry = entries.next().getValue();
      if (!entry.expires().isAfter(now)) {
        bytes -= entry.bytes().length;
        entries.remove();
      }
    }
  }
}
