package art.arcane.gloss.editor;

import art.arcane.gloss.doc.WriteNotices;
import art.arcane.gloss.editor.sync.EditorWorkspace;
import art.arcane.gloss.editor.sync.EditorWorkspaceWatch;
import art.arcane.volmlib.util.editor.EditorIdentity;
import art.arcane.volmlib.util.editor.EditorListener;
import art.arcane.volmlib.util.editor.EditorLog;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.PairingCode;
import art.arcane.volmlib.util.editor.relay.EditorRelayClient;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The editor API on this server: identity, tokens, the workspace catalog, event pacing, the embedded listener and
 * the relay client. A settings change applies live: {@code enabled} opens or closes the whole editor, a relay key
 * restarts only the relay client, a bind key restarts only the listener, the rest swaps in place. Tokens, links and
 * the identity survive every restart.
 *
 * <p>The catalog is read again only when something changed: Gloss's own writes announce themselves, and files
 * changed outside Gloss come from the platform's file events, or where the JDK has none, from comparing file times
 * once a second while a stream is open. Notes are built on their own lane, so neither reads nor the catalog wait
 * for them.
 */
public final class EditorService implements AutoCloseable {
  /** Where the JDK has no native file events: how often file times are compared while a stream is open. */
  static final Duration SCAN = Duration.ofSeconds(1);
  /** How long after Gloss's own last write a refresh waits, so a burst of writes is told once. */
  static final Duration NOTICE_QUIET = Duration.ofMillis(50);
  /** How long a Gloss write marks its file's next change as the server's. */
  static final Duration NOTICED = Duration.ofSeconds(3);
  /** How long the relay may be down before a player with an open link hears it, when no listener runs. */
  static final Duration RELAY_GRACE = Duration.ofSeconds(15);
  private static final String OLD_LINKS = "editor-sync";

  private final Path data;
  private final EditorHost host;
  private final Function<EditorSettings, EditorWorkspace> workspaces;
  private final Clock clock;
  private volatile EditorSettings settings;
  private volatile EditorApi api;
  private volatile EditorRelayClient relay;
  private volatile EditorListener listener;
  private ThreadPoolExecutor files;
  private volatile ThreadPoolExecutor notesLane;
  private ScheduledExecutorService timers;
  private ScheduledFuture<?> frames;
  private ScheduledFuture<?> pendingNotice;
  private AutoCloseable notices;
  private EditorWorkspaceWatch watch;
  private String state = "not started";
  private String listenerProblem = "";
  private Instant relayDown;
  /** Files and folders Gloss wrote lately, with when: a change found there is told as the server's. */
  private final java.util.Map<Path, Long> noticed = new java.util.concurrent.ConcurrentHashMap<>();
  private final AtomicBoolean refreshQueued = new AtomicBoolean();
  private final AtomicBoolean notesQueued = new AtomicBoolean();

  public EditorService(Path data, EditorHost host, EditorSettings settings,
                       Function<EditorSettings, EditorWorkspace> workspaces, Clock clock) {
    this.data = Objects.requireNonNull(data).toAbsolutePath().normalize();
    this.host = Objects.requireNonNull(host);
    this.settings = Objects.requireNonNull(settings);
    this.workspaces = Objects.requireNonNull(workspaces);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Opens the editor state and starts the listener and relay when enabled. A listener failure leaves the relay. */
  public synchronized void start() throws IOException {
    if (api != null) {
      throw new IllegalStateException("The editor service is already running");
    }
    open(true);
  }

  private void open(boolean starting) throws IOException {
    if (!settings.enabled()) {
      state = "disabled; set [editor] enabled = true in gloss.toml";
      return;
    }
    removeOldLinks();
    EditorIdentity identity = EditorLink.identity(data);
    EditorTokens<EditorScope> tokens = EditorLink.tokens(data, clock, host::warn);
    files = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
        Thread.ofPlatform().daemon().name("Gloss-editor-files").factory());
    notesLane = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4),
        Thread.ofPlatform().daemon().name("Gloss-editor-notes").factory());
    timers = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("Gloss-editor-timers").factory());
    EditorEvents events = new EditorEvents();
    EditorWorkspace workspace = workspaces.apply(settings);
    api = new EditorApi(new EditorApi.Parts(settings, host, identity, tokens, events, workspace,
        new EditorWarnings(host::warn, clock), new EditorNotices(host), new EditorUploads(clock,
        () -> settings.maxProjectMiB() * 1024 * 1024), files, clock));
    EditorApi opened = api;
    frames = scheduleFrames(events, settings.eventFramesPerSecond());
    timers.scheduleAtFixedRate(guard(() -> {
      try {
        opened.housekeeping();
      } catch (IOException failure) {
        host.warn("Editor token housekeeping failed: " + failure.getMessage());
      }
      relayCheck(opened);
    }), 1, 1, TimeUnit.SECONDS);
    timers.scheduleAtFixedRate(guard(opened::recheckAdmins), 60, 60, TimeUnit.SECONDS);
    watch = watchFiles();
    if (watch == null) {
      timers.scheduleAtFixedRate(guard(() -> {
        if (opened.parts().events().open() > 0) {
          refresh(true);
        }
      }), SCAN.toMillis(), SCAN.toMillis(), TimeUnit.MILLISECONDS);
    }
    notices = WriteNotices.listen(this::noticed);
    // The catalog and notes are read once right away, so changes are told against the files as they are now.
    refresh(false);
    state = "ready";
    openRelay();
    openListener(starting);
  }

  /** The old {@code editor-sync} folder holds links from before pairing, which nothing reads now; they go at start. */
  private void removeOldLinks() {
    Path old = data.resolve(OLD_LINKS);
    if (!Files.isDirectory(old, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    int links = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(old, "*.json")) {
      for (Path ignored : stream) {
        links++;
      }
    } catch (IOException unreadable) {
      links = 0;
    }
    try (Stream<Path> walk = Files.walk(old)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
      host.log("Removed " + links + " old web editor link" + (links == 1 ? "" : "s")
          + "; pair the editor again with /gloss editor pair");
    } catch (IOException failure) {
      host.warn("Could not remove the old editor-sync folder: " + failure.getMessage());
    }
  }

  private void noticed(Path touched) {
    Path path = touched.toAbsolutePath().normalize();
    if (!path.startsWith(data) || path.startsWith(data.resolve("editor"))) {
      return;
    }
    long at = System.nanoTime();
    noticed.put(path, at);
    if (noticed.size() > 4096) {
      noticed.entrySet().removeIf((java.util.Map.Entry<Path, Long> entry) -> at - entry.getValue() > NOTICED.toNanos());
    }
    refreshSoon();
  }

  /** Refreshes {@link #NOTICE_QUIET} after the first of a burst of changes, so the burst is told once. */
  private void refreshSoon() {
    synchronized (this) {
      if (timers == null || pendingNotice != null && !pendingNotice.isDone()) {
        return;
      }
      try {
        pendingNotice = timers.schedule(guard(() -> refresh(false)), NOTICE_QUIET.toMillis(),
            TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException stopped) {
        pendingNotice = null;
      }
    }
  }

  /** The platform's file events, or null where the JDK only polls; then file times are compared every {@link #SCAN}. */
  private EditorWorkspaceWatch watchFiles() {
    try {
      return EditorWorkspaceWatch.start(data, this::refreshSoon).orElse(null);
    } catch (IOException | RuntimeException failure) {
      host.warn("Editor compares file times once a second instead of watching the folder: " + failure.getMessage());
      return null;
    }
  }

  /**
   * Reads the workspace again on the file lane and sends what changed; at most one waits at a time. With
   * {@code ifMoved} it first compares file times and reads nothing when none moved. Each change is told as the
   * server's when Gloss wrote that file (or its folder) lately, else as a file changed outside Gloss.
   */
  private void refresh(boolean ifMoved) {
    EditorApi active = api;
    if (active == null || !refreshQueued.compareAndSet(false, true)) {
      return;
    }
    try {
      files.execute(() -> {
        refreshQueued.set(false);
        try {
          EditorWorkspace workspace = active.parts().workspace();
          if (!ifMoved || !workspace.read() || workspace.moved()) {
            EditorWorkspace.Delta delta = workspace.refresh();
            if (delta.contentChanged()) {
              active.parts().events().publish(EditorCatalogEvents.catalog(delta, this::sourceOf));
            }
          }
          // A publish reads the files itself; its notes are built here too.
          settleNotes(active);
        } catch (RuntimeException failure) {
          host.warn("Editor could not read the workspace: " + failure.getMessage());
        }
      });
    } catch (RejectedExecutionException full) {
      refreshQueued.set(false);
    }
  }

  /** {@code server} when Gloss wrote this data-folder path or its folder within {@link #NOTICED}, else {@code file}. */
  private EditorWorkspace.Source sourceOf(String relative) {
    Path file = data.resolve(relative).normalize();
    long now = System.nanoTime();
    for (Path candidate : new Path[] {file, file.getParent()}) {
      Long at = candidate == null ? null : noticed.get(candidate);
      if (at != null && now - at < NOTICED.toNanos()) {
        return EditorWorkspace.Source.SERVER;
      }
    }
    return EditorWorkspace.Source.FILE;
  }

  /** Rebuilds the notes on their own lane when documents changed; at most one waits at a time. */
  private void settleNotes(EditorApi active) {
    ThreadPoolExecutor lane = notesLane;
    if (lane == null || !notesQueued.compareAndSet(false, true)) {
      return;
    }
    try {
      lane.execute(() -> {
        notesQueued.set(false);
        try {
          String moved = active.parts().workspace().settleNotes();
          if (moved != null) {
            EditorWorkspace.Delta delta = new EditorWorkspace.Delta(List.of(), List.of(), List.of(), List.of(), null, moved);
            active.parts().events().publish(EditorCatalogEvents.catalog(delta, EditorWorkspace.Source.SERVER));
          }
        } catch (RuntimeException failure) {
          host.warn("Editor could not build its notes: " + failure.getMessage());
        }
      });
    } catch (RejectedExecutionException stopped) {
      notesQueued.set(false);
    }
  }

  /** Tells players with an open link once when the relay stayed unreachable and no listener runs. */
  private void relayCheck(EditorApi opened) {
    EditorRelayClient current;
    boolean direct;
    synchronized (this) {
      current = relay;
      direct = listener != null;
    }
    if (current == null || current.isRegistered() || direct) {
      relayDown = null;
      return;
    }
    Instant now = clock.instant();
    if (relayDown == null) {
      relayDown = now;
      return;
    }
    if (now.isBefore(relayDown.plus(RELAY_GRACE))) {
      return;
    }
    for (EditorTokens.Open<EditorScope> open : opened.parts().tokens().openPairings()) {
      if (opened.parts().warnings().due("relay notice " + open.tokenId(), Duration.ofHours(1))) {
        opened.parts().notices().refused(open, "the relay " + settings.relayUrl() + " is unreachable");
      }
    }
  }

  private ScheduledFuture<?> scheduleFrames(EditorEvents events, int framesPerSecond) {
    long frameMillis = Math.max(10L, 1000L / framesPerSecond);
    return timers.scheduleAtFixedRate(guard(events::tick), frameMillis, frameMillis, TimeUnit.MILLISECONDS);
  }

  /** Connects the relay once someone paired or is pairing, so a server nobody paired never dials out. */
  private void openRelay() {
    EditorApi active = api;
    if (!settings.relayEnabled() || relay != null || active == null) {
      return;
    }
    if (active.parts().tokens().list().isEmpty() && active.parts().tokens().openPairings().isEmpty()) {
      return;
    }
    relay = new EditorRelayClient(EditorLink.PRODUCT, EditorLink.relay(settings), active.parts().identity(), active,
        EditorLog.of(host::log, host::warn));
    relay.start();
    host.log("Editor relay connecting to " + settings.relayUrl() + " as " + active.parts().identity().fingerprint());
  }

  private void closeRelay() {
    if (relay != null) {
      relay.close();
    }
    relay = null;
  }

  private void openListener(boolean starting) {
    listenerProblem = "";
    if (!settings.listenerEnabled()) {
      return;
    }
    EditorListener started = new EditorListener(EditorLink.PRODUCT, EditorLink.listener(settings), data, api, host::warn);
    try {
      started.start();
      listener = started;
      host.log("Editor " + started.describe());
    } catch (IOException | RuntimeException failure) {
      started.close();
      String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      listenerProblem = failure instanceof BindException
          ? "cannot bind " + settings.listenAddress() + ":" + settings.port() + " (" + reason
          + "); set [editor] port to a free port or listenerEnabled = false"
          + (settings.relayEnabled() ? ". The relay still works" : "")
          : reason;
      host.warn("Editor listener failed: " + listenerProblem);
    }
  }

  private void closeListener() {
    if (listener != null) {
      listener.close();
    }
    listener = null;
  }

  /** Applies changed {@code [editor]} settings. */
  public synchronized void settings(EditorSettings next) {
    EditorSettings current = settings;
    if (current.equals(next)) {
      return;
    }
    settings = next;
    if (current.enabled() != next.enabled() || api == null) {
      shut();
      host.log(next.enabled() ? "Editor enabled by gloss.toml" : "Editor disabled by gloss.toml");
      try {
        open(false);
      } catch (IOException | RuntimeException failure) {
        state = "failed: " + failure.getMessage();
        host.warn("Editor settings could not be applied: " + failure.getMessage());
      }
      return;
    }
    api.settings(next);
    if (current.eventFramesPerSecond() != next.eventFramesPerSecond()) {
      if (frames != null) {
        frames.cancel(false);
      }
      frames = scheduleFrames(api.parts().events(), next.eventFramesPerSecond());
    }
    if (current.relayChanged(next)) {
      boolean wasConnected = relay != null;
      closeRelay();
      openRelay();
      if (!next.relayEnabled() && wasConnected) {
        host.log("Editor relay stopped by gloss.toml");
      }
    }
    if (current.rebind(next) || listener == null && next.listenerEnabled()) {
      closeListener();
      openListener(false);
      if (!next.listenerEnabled()) {
        host.log("Editor listener stopped by gloss.toml");
      }
    } else if (listener != null) {
      listener.settings(EditorLink.listener(next));
    }
  }

  private Runnable guard(Runnable task) {
    return () -> {
      try {
        task.run();
      } catch (RuntimeException failure) {
        host.warn("Editor task failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
      }
    };
  }

  public synchronized Optional<EditorApi> api() {
    return Optional.ofNullable(api);
  }

  public EditorSettings settings() {
    return settings;
  }

  /** A pairing link for {@code /gloss editor pair}. */
  public PairingLink pair(EditorTokens.Grant<EditorScope> grant) throws IOException {
    EditorApi active;
    synchronized (this) {
      active = api;
    }
    if (active == null) {
      throw new IOException("The editor is " + state);
    }
    Optional<String> direct = directUrl();
    Optional<String> relayUrl = settings.relayEnabled() ? Optional.of(settings.relayUrl()) : Optional.empty();
    if (direct.isEmpty() && relayUrl.isEmpty()) {
      throw new IOException("Nothing can reach this server: enable [editor] listenerEnabled or relayEnabled");
    }
    EditorTokens.Pairing<EditorScope> pairing;
    try {
      pairing = active.parts().tokens().pair(grant);
    } catch (IllegalStateException full) {
      throw new IOException(full.getMessage());
    }
    synchronized (this) {
      if (api == active) {
        openRelay();
      }
    }
    PairingCode code;
    try {
      code = new PairingCode(EditorLink.PRODUCT, direct, relayUrl, active.parts().identity().fingerprint(),
          active.parts().identity().serverKey(), pairing.tokenId(), pairing.secret(), pairing.expires(),
          grant.label().isEmpty() ? Optional.empty() : Optional.of(grant.label()), Optional.of(active.serverName()),
          grant.scopes().stream().map(EditorScope::wireName).toList(), grant.folders());
    } catch (IllegalArgumentException refused) {
      throw new IOException("Cannot build a pairing link: " + refused.getMessage());
    }
    return new PairingLink(EditorUrl.base(settings.editorUrl()) + "#/pair/" + code.encode(), pairing);
  }

  public record PairingLink(String link, EditorTokens.Pairing<EditorScope> pairing) {
  }

  /** The address a browser uses on the direct path: advertisedUrl, or the listener when TLS or loopback allow. */
  private Optional<String> directUrl() {
    if (!settings.advertisedUrl().isEmpty()) {
      return Optional.of(settings.advertisedUrl());
    }
    EditorListener active = listener;
    if (active == null) {
      return Optional.empty();
    }
    String address = settings.listenAddress();
    if (settings.tls()) {
      String host = address.contains(":") ? "[" + address + "]" : address;
      return address.equals("0.0.0.0") || address.equals("::") ? Optional.empty()
          : Optional.of("https://" + host + ":" + active.port());
    }
    if (!EditorSettings.loopback(address)) {
      return Optional.empty();
    }
    // Browsers allow plain http:// from an https:// page only to 127.0.0.1 and localhost; localhost also reaches ::1.
    return Optional.of((address.equals("127.0.0.1") ? "http://127.0.0.1:" : "http://localhost:") + active.port());
  }

  public List<String> revoke(String idOrAll) throws IOException {
    EditorApi active;
    synchronized (this) {
      active = api;
    }
    if (active == null) {
      throw new IOException("The editor is " + state);
    }
    return active.revoke(idOrAll, "token-revoked", "by command");
  }

  /** Two lines: state with listener, relay and counts; then the fingerprint and the first problem. */
  public synchronized List<String> status() {
    if (api == null) {
      return List.of("Editor " + state);
    }
    EditorApi.Parts parts = api.parts();
    String summary = "Editor " + state + " | " + (listener == null ? listenerState() : listener.describe()) + " | "
        + relayState(true) + " | " + counts(parts) + " | " + parts.events().open() + " streams";
    String problem = problem();
    return List.of(summary, "Fingerprint " + parts.identity().fingerprint() + (problem.isEmpty() ? "" : " | " + problem));
  }

  /** The short form of {@link #status} for chat. */
  public synchronized String headline() {
    if (api == null) {
      return "Editor " + state;
    }
    String problem = problem();
    return "Editor " + state + " | " + (listener == null ? listenerState() : "listener :" + listener.port()) + " | "
        + relayState(false) + " | " + counts(api.parts()) + (problem.isEmpty() ? "" : " | " + problem);
  }

  private String relayState(boolean full) {
    if (relay != null) {
      String state = relay.isRegistered() ? "registered"
          : relay.unreachableReason().isPresent() ? "unreachable" : "connecting";
      return "relay " + (full ? settings.relayUrl() + " " : "") + state;
    }
    return settings.relayEnabled() ? "relay waits for the first pairing" : "relay off";
  }

  private String listenerState() {
    if (!settings.listenerEnabled()) {
      return "listener off";
    }
    return listenerProblem.isEmpty() ? "listener off" : "listener failed";
  }

  private static String counts(EditorApi.Parts parts) {
    int waiting = parts.tokens().openPairings().size();
    int tokens = parts.tokens().list().size();
    return tokens + (tokens == 1 ? " token" : " tokens") + (waiting == 0 ? "" : ", " + waiting
        + (waiting == 1 ? " pairing" : " pairings"));
  }

  /** What keeps the editor from working fully, or empty. */
  public synchronized String problem() {
    if (api == null) {
      return "";
    }
    if (listener == null && settings.listenerEnabled()) {
      return "listener failed" + (listenerProblem.isEmpty() ? "" : ": " + listenerProblem);
    }
    if (listener == null && !settings.relayEnabled()) {
      return "no listener or relay; turn one on in [editor]";
    }
    Optional<String> relayFailure = relay == null ? Optional.empty() : relay.unreachableReason();
    if (relayFailure.isPresent()) {
      return "relay unreachable (" + relayFailure.get() + "); " + (listener != null
          ? "the direct listener still works" : "no browser reaches this server until it answers");
    }
    return "";
  }

  /** The bound listener port, or -1. */
  public synchronized int listenerPort() {
    return listener == null ? -1 : listener.port();
  }

  public synchronized boolean relayRegistered() {
    return relay != null && relay.isRegistered();
  }

  @Override
  public synchronized void close() {
    shut();
    state = "stopped";
  }

  private void shut() {
    if (notices != null) {
      try {
        notices.close();
      } catch (Exception ignored) {
        // Removing a listener from a list does not fail.
      }
      notices = null;
    }
    if (watch != null) {
      watch.close();
      watch = null;
    }
    if (api != null) {
      api.close();
    }
    closeRelay();
    closeListener();
    api = null;
    if (timers != null) {
      timers.shutdownNow();
    }
    timers = null;
    frames = null;
    pendingNotice = null;
    if (files != null) {
      files.shutdown();
      try {
        if (!files.awaitTermination(5, TimeUnit.SECONDS)) {
          files.shutdownNow();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        files.shutdownNow();
      }
    }
    files = null;
    if (notesLane != null) {
      notesLane.shutdownNow();
    }
    notesLane = null;
    refreshQueued.set(false);
    notesQueued.set(false);
    listenerProblem = "";
  }
}
