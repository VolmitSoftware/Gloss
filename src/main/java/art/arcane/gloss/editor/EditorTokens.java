package art.arcane.gloss.editor;

import art.arcane.gloss.doc.AtomicFiles;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Editor tokens in {@code editor/tokens.json}: only a SHA-256 of each token is stored, with its label, issuing
 * player, scopes, folders, expiry, lifetime, last use and the last accepted {@code X-Gloss-Counter}. Expiry
 * slides: each use moves it to one lifetime after that use. Pairing secrets live in memory only.
 *
 * <p>Changes are made under this object's lock and written after it is released, so a slow disk holds up only the
 * request that changed something. Each write is forced to disk; one that fails is tried again by {@link #expire}.
 */
public final class EditorTokens {
  public static final String PATH = "editor/tokens.json";
  public static final int MAXIMUM_TOKENS = 256;
  public static final int MAXIMUM_PAIRINGS = 64;
  public static final Duration PAIRING_LIFETIME = Duration.ofMinutes(10);
  /**
   * A slid expiry is written once it moved this far past the stored one, or a quarter of the token's lifetime when
   * that is shorter, so use does not write on every request.
   */
  static final Duration SLIDE_WRITE = Duration.ofHours(1);
  private static final DateTimeFormatter DAMAGED = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final Pattern FOLDER = Pattern.compile("[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,7}");
  private static final Pattern BEARER = Pattern.compile("gls_tok_[A-Za-z0-9_-]{8,60}\\.[A-Za-z0-9_-]{43}");

  /**
   * A stored token. {@code admin} is empty for tokens paired from the console. {@code adminChecked} is when the
   * server last confirmed the player still holds {@code gloss.editor.pair}; pairing counts as one.
   */
  public record Token(String id, String hash, String label, Optional<UUID> admin, List<EditorScope> scopes,
                      List<String> folders, Instant created, Instant expires, Duration lifetime,
                      Optional<Instant> lastUsed, long counter, Optional<String> client, Instant adminChecked) {
    public Token {
      scopes = List.copyOf(scopes);
      folders = List.copyOf(folders);
    }

    private Token used(Instant now, Instant until) {
      return new Token(id, hash, label, admin, scopes, folders, created, until, lifetime, Optional.of(now), counter,
          client, adminChecked);
    }

    private Token counted(long value) {
      return new Token(id, hash, label, admin, scopes, folders, created, expires, lifetime, lastUsed, value, client,
          adminChecked);
    }

    private Token checked(Instant now) {
      return new Token(id, hash, label, admin, scopes, folders, created, expires, lifetime, lastUsed, counter, client,
          now);
    }

    private Token scoped(List<EditorScope> kept) {
      return new Token(id, hash, label, admin, kept, folders, created, expires, lifetime, lastUsed, counter, client,
          adminChecked);
    }

    public boolean has(EditorScope scope) {
      return scopes.contains(scope);
    }

    /**
     * True when the token reaches a document or image in {@code folder} (the part of its id or path before the
     * last slash, empty for a flat id). A token without folders reaches everything.
     */
    public boolean reaches(String folder) {
      return folders.isEmpty() || folders.stream()
          .anyMatch((String allowed) -> folder.equals(allowed) || folder.startsWith(allowed + "/"));
    }
  }

  /** What {@code /gloss editor pair} asked for. */
  public record Grant(String label, Optional<UUID> admin, List<EditorScope> scopes, List<String> folders,
                      Duration lifetime) {
    public Grant {
      if (label.length() > 64) {
        throw new IllegalArgumentException("label: at most 64 characters");
      }
      if (scopes.isEmpty()) {
        throw new IllegalArgumentException("scopes: at least one scope");
      }
      for (String folder : folders) {
        if (!validFolder(folder)) {
          throw new IllegalArgumentException("folders take folder names such as shop or shop/sub; " + folder
              + " is not one");
        }
      }
      if (lifetime.isNegative() || lifetime.compareTo(Duration.ofMinutes(1)) < 0
          || lifetime.compareTo(Duration.ofDays(365)) > 0) {
        throw new IllegalArgumentException("expires: 1 minute to 365 days");
      }
      scopes = List.copyOf(scopes);
      folders = List.copyOf(folders);
    }
  }

  /** A pairing waiting for its browser. */
  public record Pairing(String tokenId, String secret, Instant expires, Grant grant) {
  }

  /** A pairing link not opened yet, without its secret. */
  public record Open(String tokenId, Instant expires, Grant grant) {
  }

  private record Pending(String secretHash, Instant expires, Grant grant) {
  }

  /** An ended token, remembered for a day so its browser hears why: revoked or expired. */
  private record Ended(Instant expires, boolean expired) {
  }

  /** The store's content at one moment; the number keeps an older one from replacing a newer one on disk. */
  private record Snapshot(long version, byte[] bytes) {
  }

  /** A revoke that holds in memory but did not reach the disk; {@link #expire} writes it again. */
  public static final class Unsaved extends IOException {
    private final List<String> ids;

    Unsaved(List<String> ids, IOException cause) {
      super(PATH + " could not be written (" + cause.getMessage() + ")", cause);
      this.ids = List.copyOf(ids);
    }

    public List<String> ids() {
      return ids;
    }
  }

  private final Path data;
  private final Clock clock;
  private final Consumer<String> warn;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Token> tokens = new LinkedHashMap<>();
  private final Map<String, Ended> ended = new LinkedHashMap<>();
  private final Map<String, Pending> pairings = new LinkedHashMap<>();
  /** Each token's expiry as last handed to the disk. */
  private final Map<String, Instant> stored = new HashMap<>();
  private final Object writing = new Object();
  private long version;
  /** The last write failed, so the next {@link #expire} writes again. */
  private boolean unsaved;
  /** Guarded by {@link #writing}: the newest snapshot on disk. */
  private long written;

  public EditorTokens(Path data, Clock clock, Consumer<String> warn) throws IOException {
    this.data = Objects.requireNonNull(data);
    this.clock = Objects.requireNonNull(clock);
    this.warn = Objects.requireNonNull(warn);
    load();
  }

  public static boolean validFolder(String folder) {
    return folder != null && FOLDER.matcher(folder).matches();
  }

  /** Starts a pairing; the code carries the secret, which is good once for ten minutes. */
  public synchronized Pairing pair(Grant grant) {
    prunePairings();
    if (openPairings().size() >= MAXIMUM_PAIRINGS || pairings.size() >= MAXIMUM_PAIRINGS * 4) {
      throw new IllegalStateException("Too many pairing links are waiting; let some expire or revoke them first");
    }
    if (tokens.size() >= MAXIMUM_TOKENS) {
      throw new IllegalStateException("Token limit of " + MAXIMUM_TOKENS + " reached; revoke unused tokens first");
    }
    String id = "tok_" + randomText(12);
    String secret = randomText(32);
    Instant expires = now().plus(PAIRING_LIFETIME);
    pairings.put(id, new Pending(sha256(secret), expires, grant));
    return new Pairing(id, secret, expires, grant);
  }

  /** Exchanges a pairing secret for a token. Returns the token text, which is shown once and never stored. */
  public Map.Entry<String, Token> exchange(String tokenId, String secret, Optional<String> client)
      throws EditorError, IOException {
    String text;
    Token token;
    Snapshot snapshot;
    synchronized (this) {
      Pending pending = pairings.get(tokenId);
      if (pending == null || !MessageDigest.isEqual(pending.secretHash().getBytes(StandardCharsets.US_ASCII),
          sha256(secret).getBytes(StandardCharsets.US_ASCII))) {
        throw new EditorError(EditorError.Code.PAIRING_INVALID, "This pairing link is not known to the server.");
      }
      pairings.remove(tokenId);
      if (!now().isBefore(pending.expires())) {
        throw new EditorError(EditorError.Code.PAIRING_EXPIRED, "This pairing link expired; run /gloss editor pair again.");
      }
      text = "gls_" + tokenId + "." + randomText(32);
      Instant created = now();
      Grant grant = pending.grant();
      token = new Token(tokenId, sha256(text), grant.label(), grant.admin(), grant.scopes(), grant.folders(),
          created, created.plus(grant.lifetime()), grant.lifetime(), Optional.empty(), 0L,
          client.map((String name) -> name.length() > 128 ? name.substring(0, 128) : name), created);
      tokens.put(tokenId, token);
      snapshot = snapshot();
    }
    write(snapshot);
    return Map.entry(text, token);
  }

  /** The token a bearer value names, or why it is refused. Constant-time hash comparison. Slides the expiry. */
  public Token authenticate(String bearer) throws EditorError {
    if (bearer == null || !BEARER.matcher(bearer).matches()) {
      throw new EditorError(EditorError.Code.UNAUTHORIZED, "A valid editor token is required.");
    }
    String id = bearer.substring(4, bearer.indexOf('.'));
    Token used;
    Snapshot snapshot = null;
    synchronized (this) {
      Ended gone = ended.get(id);
      if (gone != null) {
        throw gone.expired()
            ? new EditorError(EditorError.Code.TOKEN_EXPIRED, "This token expired; pair the browser again.")
            : new EditorError(EditorError.Code.TOKEN_REVOKED, "This token was revoked.");
      }
      Token token = tokens.get(id);
      if (token == null || !MessageDigest.isEqual(token.hash().getBytes(StandardCharsets.US_ASCII),
          sha256(bearer).getBytes(StandardCharsets.US_ASCII))) {
        throw new EditorError(EditorError.Code.UNAUTHORIZED, "A valid editor token is required.");
      }
      Instant now = now();
      if (!now.isBefore(token.expires())) {
        throw new EditorError(EditorError.Code.TOKEN_EXPIRED, "This token expired; pair the browser again.");
      }
      Instant slid = now.plus(token.lifetime());
      used = token.used(now, slid.isAfter(token.expires()) ? slid : token.expires());
      tokens.put(id, used);
      Duration step = token.lifetime().dividedBy(4);
      if (step.compareTo(SLIDE_WRITE) > 0) {
        step = SLIDE_WRITE;
      }
      if (slid.isAfter(stored.getOrDefault(id, token.expires()).plus(step))) {
        snapshot = snapshot();
      }
    }
    if (snapshot != null) {
      try {
        write(snapshot);
      } catch (IOException failure) {
        warn.accept("Editor could not write " + PATH + " (" + failure.getMessage() + ") with the new expiry of " + id
            + "; Gloss tries again every second");
      }
    }
    return used;
  }

  /** Refuses a token revoked or expired since its request was authenticated, as queued work starts. */
  public synchronized void stillValid(String tokenId) throws EditorError {
    Token token = tokens.get(tokenId);
    if (token == null) {
      throw new EditorError(EditorError.Code.TOKEN_REVOKED, "This token was revoked.");
    }
    if (!now().isBefore(token.expires())) {
      throw new EditorError(EditorError.Code.TOKEN_EXPIRED, "This token expired; pair the browser again.");
    }
  }

  /** Accepts a counted request's counter, which must exceed every earlier one, and stores it first. */
  public void advance(String tokenId, long counter) throws EditorError, IOException {
    Snapshot snapshot;
    synchronized (this) {
      Token token = tokens.get(tokenId);
      if (token == null) {
        throw new EditorError(EditorError.Code.TOKEN_REVOKED, "This token was revoked.");
      }
      if (counter <= token.counter()) {
        JsonObject extra = new JsonObject();
        extra.addProperty("counter", token.counter());
        throw new EditorError(EditorError.Code.COUNTER_REPLAYED, "X-Gloss-Counter must exceed " + token.counter() + ".",
            -1L, extra);
      }
      tokens.put(tokenId, token.counted(counter));
      snapshot = snapshot();
    }
    write(snapshot);
  }

  /** Records that the token's player still holds {@code gloss.editor.pair}. Writes at most once an hour. */
  public void confirmAdmin(String tokenId) throws IOException {
    Snapshot snapshot;
    synchronized (this) {
      Token token = tokens.get(tokenId);
      if (token == null || token.adminChecked().plus(Duration.ofHours(1)).isAfter(now())) {
        return;
      }
      tokens.put(tokenId, token.checked(now()));
      snapshot = snapshot();
    }
    write(snapshot);
  }

  /** Takes one scope away from a token, keeping the rest; false when the token is gone or never had it. */
  public boolean dropScope(String tokenId, EditorScope scope) throws IOException {
    Snapshot snapshot;
    synchronized (this) {
      Token token = tokens.get(tokenId);
      if (token == null || !token.has(scope)) {
        return false;
      }
      List<EditorScope> kept = token.scopes().stream().filter((EditorScope held) -> held != scope).toList();
      if (kept.isEmpty()) {
        return false;
      }
      tokens.put(tokenId, token.scoped(kept));
      snapshot = snapshot();
    }
    write(snapshot);
    return true;
  }

  /**
   * Revokes one token, or every token for {@code all}; links not opened yet are cancelled too. Returns the ids. The
   * revoke holds at once; when it cannot be written, {@link Unsaved} names the ids and {@link #expire} writes again.
   */
  public List<String> revoke(String idOrAll) throws IOException {
    List<String> revoked;
    Snapshot snapshot = null;
    synchronized (this) {
      boolean all = idOrAll.equals("all");
      List<String> ids = all ? new ArrayList<>(tokens.keySet())
          : tokens.containsKey(idOrAll) ? new ArrayList<>(List.of(idOrAll)) : new ArrayList<>();
      Instant now = now();
      for (Iterator<Map.Entry<String, Pending>> open = pairings.entrySet().iterator(); open.hasNext(); ) {
        Map.Entry<String, Pending> pairing = open.next();
        if (!all && !pairing.getKey().equals(idOrAll)) {
          continue;
        }
        open.remove();
        if (now.isBefore(pairing.getValue().expires())) {
          ids.add(pairing.getKey());
        }
      }
      boolean changed = false;
      for (String id : ids) {
        Token token = tokens.remove(id);
        if (token == null) {
          continue;
        }
        ended.put(id, new Ended(token.expires(), false));
        changed = true;
      }
      if (changed) {
        snapshot = snapshot();
      }
      revoked = List.copyOf(ids);
    }
    if (snapshot != null) {
      try {
        write(snapshot);
      } catch (IOException failure) {
        throw new Unsaved(revoked, failure);
      }
    }
    return revoked;
  }

  /**
   * Ends tokens past their expiry and forgets old revocations. Returns the ids that expired now. Also writes again
   * what an earlier write could not; that retry fails quietly, since the first failure was already reported.
   */
  public List<String> expire() throws IOException {
    List<String> expired = new ArrayList<>();
    Snapshot snapshot = null;
    boolean retryOnly;
    synchronized (this) {
      Instant now = now();
      for (Token token : tokens.values()) {
        if (!now.isBefore(token.expires())) {
          expired.add(token.id());
        }
      }
      boolean changed = !expired.isEmpty();
      for (String id : expired) {
        ended.put(id, new Ended(tokens.remove(id).expires(), true));
      }
      Iterator<Map.Entry<String, Ended>> old = ended.entrySet().iterator();
      while (old.hasNext()) {
        if (old.next().getValue().expires().plus(Duration.ofDays(1)).isBefore(now)) {
          old.remove();
          changed = true;
        }
      }
      prunePairings();
      retryOnly = !changed;
      if (changed || unsaved) {
        snapshot = snapshot();
      }
    }
    if (snapshot != null) {
      try {
        write(snapshot);
      } catch (IOException failure) {
        if (!retryOnly) {
          throw failure;
        }
      }
    }
    return expired;
  }

  public synchronized List<Token> list() {
    return List.copyOf(tokens.values());
  }

  public synchronized Optional<Token> find(String id) {
    return Optional.ofNullable(tokens.get(id));
  }

  /** Pairing links not opened yet and not expired. */
  public synchronized List<Open> openPairings() {
    prunePairings();
    Instant now = now();
    List<Open> open = new ArrayList<>();
    pairings.forEach((String id, Pending pending) -> {
      if (now.isBefore(pending.expires())) {
        open.add(new Open(id, pending.expires(), pending.grant()));
      }
    });
    return open;
  }

  /** The waiting pairing with this id, expired or not, until it is opened or pruned. */
  public synchronized Optional<Open> pairing(String tokenId) {
    Pending pending = pairings.get(tokenId);
    return pending == null ? Optional.empty() : Optional.of(new Open(tokenId, pending.expires(), pending.grant()));
  }

  private void prunePairings() {
    Instant now = now();
    // Expired links stay an hour so a late browser learns the link expired instead of that it never existed.
    pairings.values().removeIf((Pending pending) -> now.isAfter(pending.expires().plus(Duration.ofHours(1))));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }

  private String randomText(int bytes) {
    byte[] value = new byte[bytes];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException(missing);
    }
  }

  /** Reads the store; a damaged one is moved aside with one warning, and the editor starts with no tokens. */
  private void load() throws IOException {
    Path file = data.resolve(PATH);
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      read(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
    } catch (CharacterCodingException | RuntimeException damaged) {
      tokens.clear();
      ended.clear();
      String why = damaged.getMessage() == null ? damaged.getClass().getSimpleName()
          : damaged.getMessage().lines().findFirst().orElse("");
      Path aside = quarantine(file);
      warn.accept(PATH + " is damaged (" + why + "); moved it to editor/" + aside.getFileName()
          + " and started with no paired browsers, so each one pairs again");
    }
    tokens.forEach((String id, Token token) -> stored.put(id, token.expires()));
  }

  private void read(JsonObject saved) {
    for (JsonElement element : saved.getAsJsonArray("tokens")) {
      JsonObject entry = element.getAsJsonObject();
      List<EditorScope> scopes = new ArrayList<>();
      for (JsonElement scope : entry.getAsJsonArray("scopes")) {
        scopes.add(EditorScope.parse(scope.getAsString())
            .orElseThrow(() -> new IllegalArgumentException("unknown scope " + scope)));
      }
      List<String> folders = new ArrayList<>();
      for (JsonElement folder : entry.getAsJsonArray("folders")) {
        folders.add(folder.getAsString());
      }
      Instant created = Instant.parse(entry.get("created").getAsString());
      Token token = new Token(entry.get("id").getAsString(), entry.get("hash").getAsString(),
          entry.get("label").getAsString(), optional(entry, "admin").map(UUID::fromString), scopes, folders, created,
          Instant.parse(entry.get("expires").getAsString()), Duration.ofSeconds(entry.get("lifetimeSeconds").getAsLong()),
          optional(entry, "lastUsed").map(Instant::parse), entry.get("counter").getAsLong(), optional(entry, "client"),
          optional(entry, "adminChecked").map(Instant::parse).orElse(created));
      tokens.put(token.id(), token);
    }
    if (saved.has("ended")) {
      for (JsonElement element : saved.getAsJsonArray("ended")) {
        JsonObject entry = element.getAsJsonObject();
        ended.put(entry.get("id").getAsString(), new Ended(Instant.parse(entry.get("expires").getAsString()),
            entry.get("expired").getAsBoolean()));
      }
    }
  }

  /** Moves a damaged store to {@code tokens.damaged-<UTC time>.json} beside it, where it stays for inspection. */
  private Path quarantine(Path file) throws IOException {
    String stamp = DAMAGED.format(clock.instant());
    Path aside = file.resolveSibling("tokens.damaged-" + stamp + ".json");
    for (int copy = 2; Files.exists(aside, LinkOption.NOFOLLOW_LINKS); copy++) {
      aside = file.resolveSibling("tokens.damaged-" + stamp + "-" + copy + ".json");
    }
    Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE);
    return aside;
  }

  /** The store as it is now, numbered; called holding this object's lock. */
  private Snapshot snapshot() {
    JsonObject saved = new JsonObject();
    saved.addProperty("schemaVersion", 1);
    JsonArray list = new JsonArray();
    for (Token token : tokens.values()) {
      JsonObject entry = new JsonObject();
      entry.addProperty("id", token.id());
      entry.addProperty("hash", token.hash());
      entry.addProperty("label", token.label());
      token.admin().ifPresent((UUID admin) -> entry.addProperty("admin", admin.toString()));
      JsonArray scopes = new JsonArray();
      token.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
      entry.add("scopes", scopes);
      JsonArray folders = new JsonArray();
      token.folders().forEach(folders::add);
      entry.add("folders", folders);
      entry.addProperty("created", token.created().toString());
      entry.addProperty("expires", token.expires().toString());
      entry.addProperty("lifetimeSeconds", token.lifetime().toSeconds());
      token.lastUsed().ifPresent((Instant used) -> entry.addProperty("lastUsed", used.toString()));
      entry.addProperty("counter", token.counter());
      token.client().ifPresent((String client) -> entry.addProperty("client", client));
      entry.addProperty("adminChecked", token.adminChecked().toString());
      list.add(entry);
    }
    saved.add("tokens", list);
    JsonArray gone = new JsonArray();
    ended.forEach((String id, Ended end) -> {
      JsonObject entry = new JsonObject();
      entry.addProperty("id", id);
      entry.addProperty("expires", end.expires().toString());
      entry.addProperty("expired", end.expired());
      gone.add(entry);
    });
    saved.add("ended", gone);
    stored.clear();
    tokens.forEach((String id, Token token) -> stored.put(id, token.expires()));
    byte[] bytes = (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(saved) + "\n")
        .getBytes(StandardCharsets.UTF_8);
    return new Snapshot(++version, bytes);
  }

  /**
   * Writes the snapshot durably unless a newer one is on disk already, which holds this one's changes too. Called
   * without this object's lock.
   */
  private void write(Snapshot snapshot) throws IOException {
    synchronized (writing) {
      if (snapshot.version() <= written) {
        return;
      }
      try {
        AtomicFiles.replaceDurable(data.resolve(PATH), snapshot.bytes());
      } catch (IOException failure) {
        synchronized (this) {
          unsaved = true;
        }
        throw failure;
      }
      written = snapshot.version();
    }
    synchronized (this) {
      if (version == snapshot.version()) {
        unsaved = false;
      }
    }
  }

  private static Optional<String> optional(JsonObject entry, String key) {
    JsonElement value = entry.get(key);
    return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());
  }
}
