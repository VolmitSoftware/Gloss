package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.volmlib.util.editor.EditorRefusal;
import art.arcane.volmlib.util.editor.EditorTokens;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class EditorTokensTest {
  @TempDir
  Path data;

  private final EditorTestServer.MutableClock clock = new EditorTestServer.MutableClock();

  private final List<String> warnings = new CopyOnWriteArrayList<>();

  private EditorTokens<EditorScope> tokens() throws IOException {
    return EditorLink.tokens(data, clock, warnings::add);
  }

  private EditorTokens.Grant<EditorScope> grant(List<String> folders, Duration lifetime) {
    return new EditorTokens.Grant<>("laptop", Optional.of(UUID.randomUUID()), EditorScope.DEFAULT_PAIRING, folders, lifetime);
  }

  @Test
  void aSecretIsGoodOnceAndOnlyTheHashIsStored() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));

    Map.Entry<String, EditorTokens.Token<EditorScope>> issued = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.of("Firefox"));
    EditorRefusal again = assertThrows(EditorRefusal.class,
        () -> tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()));
    String stored = Files.readString(data.resolve(EditorLink.TOKENS_PATH));

    assertEquals(EditorRefusal.Code.PAIRING_USED, again.code());
    assertTrue(issued.getKey().startsWith("gls_" + pairing.tokenId() + "."));
    assertFalse(stored.contains(issued.getKey()));
    assertFalse(stored.contains(pairing.secret()));
    assertTrue(stored.contains("Firefox"));
  }

  @Test
  void aWrongSecretIsUnknownAndALateOneExpired() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));

    EditorRefusal wrong = assertThrows(EditorRefusal.class,
        () -> tokens.exchange(pairing.tokenId(), "A".repeat(43), Optional.empty()));
    clock.advance(EditorTokens.PAIRING_LIFETIME.plusSeconds(1));
    EditorRefusal late = assertThrows(EditorRefusal.class,
        () -> tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()));

    assertEquals(EditorRefusal.Code.PAIRING_INVALID, wrong.code());
    assertEquals(EditorRefusal.Code.PAIRING_EXPIRED, late.code());
  }

  @Test
  void expirySlidesWithUseAndEndsAfterALifetimeUnused() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofHours(12)));
    String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();

    clock.advance(Duration.ofHours(11));
    EditorTokens.Token<EditorScope> used = tokens.authenticate(bearer);
    clock.advance(Duration.ofHours(11));
    tokens.authenticate(bearer);
    clock.advance(Duration.ofHours(13));
    EditorRefusal expired = assertThrows(EditorRefusal.class, () -> tokens.authenticate(bearer));

    assertEquals(clock.instant().minus(Duration.ofHours(13)).minus(Duration.ofHours(11)).plus(Duration.ofHours(12)),
        used.expires());
    assertEquals(EditorRefusal.Code.TOKEN_EXPIRED, expired.code());
    assertEquals(List.of(pairing.tokenId()), tokens.expire());
    assertEquals(EditorRefusal.Code.TOKEN_EXPIRED,
        assertThrows(EditorRefusal.class, () -> tokens().authenticate(bearer)).code());
  }

  @Test
  void revokedTokensAndLinksAreRefusedAndTheStoreSurvivesARestart() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> kept = tokens.pair(grant(List.of("shop"), Duration.ofDays(7)));
    String keptBearer = tokens.exchange(kept.tokenId(), kept.secret(), Optional.empty()).getKey();
    EditorTokens.Pairing<EditorScope> gone = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    String goneBearer = tokens.exchange(gone.tokenId(), gone.secret(), Optional.empty()).getKey();
    EditorTokens.Pairing<EditorScope> link = tokens.pair(grant(List.of(), Duration.ofDays(7)));

    assertEquals(List.of(gone.tokenId()), tokens.revoke(gone.tokenId()));
    assertEquals(List.of(link.tokenId()), tokens.revoke(link.tokenId()));
    EditorTokens<EditorScope> reloaded = tokens();

    assertEquals(EditorRefusal.Code.TOKEN_REVOKED,
        assertThrows(EditorRefusal.class, () -> reloaded.authenticate(goneBearer)).code());
    assertEquals(List.of("shop"), reloaded.authenticate(keptBearer).folders());
    assertTrue(reloaded.openPairings().isEmpty());
  }

  @Test
  void aDamagedStoreIsMovedAsideAndTheEditorStartsWithNoTokens() throws Exception {
    Path store = data.resolve(EditorLink.TOKENS_PATH);
    Files.createDirectories(store.getParent());
    String torn = "{\"schemaVersion\":1,\"tokens\":[{\"id\":\"tok_";
    Files.writeString(store, torn);

    EditorTokens<EditorScope> tokens = tokens();
    List<Path> aside;
    try (Stream<Path> files = Files.list(store.getParent())) {
      aside = files.filter((Path file) -> file.getFileName().toString().startsWith("tokens.damaged-")).toList();
    }
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    assertEquals(1, aside.size());
    assertEquals(torn, Files.readString(aside.getFirst()));
    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().contains("editor/" + aside.getFirst().getFileName()), warnings.getFirst());
    assertEquals(1, tokens().list().size());
  }

  @Test
  void aRevokeThatCannotBeSavedHoldsAndIsSavedOnceTheFolderTakesWritesAgain() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();
    Path folder = data.resolve(EditorLink.TOKENS_PATH).getParent();
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeFalse(Files.isWritable(folder), "this user writes into read-only folders");
      assertThrows(IOException.class, () -> tokens.revoke(pairing.tokenId()));
      assertEquals(EditorRefusal.Code.TOKEN_REVOKED,
          assertThrows(EditorRefusal.class, () -> tokens.authenticate(bearer)).code());
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    tokens.expire();

    assertEquals(EditorRefusal.Code.TOKEN_REVOKED,
        assertThrows(EditorRefusal.class, () -> tokens().authenticate(bearer)).code());
  }

  @Test
  void aShortLifetimeSavesItsSlidExpiry() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofMinutes(20)));
    String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();

    clock.advance(Duration.ofMinutes(6));
    EditorTokens.Token<EditorScope> used = tokens.authenticate(bearer);

    assertEquals(used.expires(), tokens().find(pairing.tokenId()).orElseThrow().expires());
  }

  @Test
  void steadyUseSavesTheSlidExpiryAtLeastHourly() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();

    for (int use = 0; use < 6; use++) {
      clock.advance(Duration.ofMinutes(20));
      tokens.authenticate(bearer);
    }

    Instant saved = tokens().find(pairing.tokenId()).orElseThrow().expires();
    assertFalse(saved.isBefore(clock.instant().plus(Duration.ofDays(7)).minus(Duration.ofHours(1))), saved.toString());
  }

  @Test
  void countersOnlyGoUp() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    tokens.advance(pairing.tokenId(), 3L);
    EditorRefusal replayed = assertThrows(EditorRefusal.class, () -> tokens.advance(pairing.tokenId(), 3L));

    assertEquals(EditorRefusal.Code.COUNTER_REPLAYED, replayed.code());
    assertEquals(3L, replayed.extra().get("counter").getAsLong());
    assertEquals(3L, tokens().find(pairing.tokenId()).orElseThrow().counter());
  }

  @Test
  void droppingPublishCommandsKeepsTheRest() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(new EditorTokens.Grant<>("", Optional.empty(),
        List.of(EditorScope.READ, EditorScope.PUBLISH_COMMANDS), List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    assertTrue(tokens.dropScope(pairing.tokenId(), EditorScope.PUBLISH_COMMANDS));
    assertEquals(List.of(EditorScope.READ), tokens.find(pairing.tokenId()).orElseThrow().scopes());
  }

  @Test
  void foldersReachThemselvesAndWhatIsInsideOnly() {
    EditorTokens.Token<EditorScope> token = new EditorTokens.Token<>("tok_x", "h", "", Optional.empty(), List.of(EditorScope.READ),
        List.of("shop"), clock.instant(), clock.instant(), Duration.ofDays(1), Optional.empty(), 0L, Optional.empty(),
        clock.instant());
    EditorTokens.Token<EditorScope> open = new EditorTokens.Token<>("tok_y", "h", "", Optional.empty(), List.of(EditorScope.READ),
        List.of(), clock.instant(), clock.instant(), Duration.ofDays(1), Optional.empty(), 0L, Optional.empty(),
        clock.instant());

    assertTrue(token.reaches("shop"));
    assertTrue(token.reaches("shop/sub"));
    assertFalse(token.reaches("shopping"));
    assertFalse(token.reaches(""));
    assertTrue(open.reaches(""));
    assertFalse(EditorLink.validFolder("Shop"));
    assertFalse(EditorLink.validFolder("shop/"));
    assertTrue(EditorLink.validFolder("shop/sub-2"));
  }

  @Test
  void theWaitingLinkAndTokenLimitsAreEnforced() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    for (int index = 0; index < EditorTokens.MAXIMUM_PAIRINGS; index++) {
      tokens.pair(grant(List.of(), Duration.ofDays(7)));
    }

    IllegalStateException full = assertThrows(IllegalStateException.class,
        () -> tokens.pair(grant(List.of(), Duration.ofDays(7))));
    assertTrue(full.getMessage().startsWith("Too many pairing links are waiting"));
  }

  @Test
  void theStoreIsReadableJson() throws Exception {
    EditorTokens<EditorScope> tokens = tokens();
    EditorTokens.Pairing<EditorScope> pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    JsonObject stored = JsonParser.parseString(Files.readString(data.resolve(EditorLink.TOKENS_PATH))).getAsJsonObject();

    assertEquals(1, stored.getAsJsonArray("tokens").size());
    assertEquals(604800L, stored.getAsJsonArray("tokens").get(0).getAsJsonObject().get("lifetimeSeconds").getAsLong());
  }

  @Test
  void everyKindHasAPublishScopeAndTheDefaultLeavesOutCommands() {
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      assertTrue(EditorScope.forKind(kind.wireName()).isPresent(), kind.wireName());
    }
    assertFalse(EditorScope.DEFAULT_PAIRING.contains(EditorScope.PUBLISH_COMMANDS));
    assertEquals(EditorScope.values().length - 1, EditorScope.DEFAULT_PAIRING.size());
    assertEquals(Optional.of(EditorScope.PUBLISH_SCREENS), EditorScope.forKind("panel"));
    assertEquals(Optional.empty(), EditorScope.forKind("leaderboards"));
  }
}
