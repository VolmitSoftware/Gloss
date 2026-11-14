package art.arcane.gloss.editor;

import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
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

  private EditorTokens tokens() throws IOException {
    return new EditorTokens(data, clock, warnings::add);
  }

  private EditorTokens.Grant grant(List<String> folders, Duration lifetime) {
    return new EditorTokens.Grant("laptop", Optional.of(UUID.randomUUID()), EditorScope.DEFAULT_PAIRING, folders, lifetime);
  }

  @Test
  void aSecretIsGoodOnceAndOnlyTheHashIsStored() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));

    Map.Entry<String, EditorTokens.Token> issued = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.of("Firefox"));
    EditorError again = assertThrows(EditorError.class,
        () -> tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()));
    String stored = Files.readString(data.resolve(EditorTokens.PATH));

    assertEquals(EditorError.Code.PAIRING_INVALID, again.code());
    assertTrue(issued.getKey().startsWith("gls_" + pairing.tokenId() + "."));
    assertFalse(stored.contains(issued.getKey()));
    assertFalse(stored.contains(pairing.secret()));
    assertTrue(stored.contains("Firefox"));
  }

  @Test
  void aWrongSecretIsUnknownAndALateOneExpired() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));

    EditorError wrong = assertThrows(EditorError.class,
        () -> tokens.exchange(pairing.tokenId(), "A".repeat(43), Optional.empty()));
    clock.advance(EditorTokens.PAIRING_LIFETIME.plusSeconds(1));
    EditorError late = assertThrows(EditorError.class,
        () -> tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()));

    assertEquals(EditorError.Code.PAIRING_INVALID, wrong.code());
    assertEquals(EditorError.Code.PAIRING_EXPIRED, late.code());
  }

  @Test
  void expirySlidesWithUseAndEndsAfterALifetimeUnused() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofHours(12)));
    String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();

    clock.advance(Duration.ofHours(11));
    EditorTokens.Token used = tokens.authenticate(bearer);
    clock.advance(Duration.ofHours(11));
    tokens.authenticate(bearer);
    clock.advance(Duration.ofHours(13));
    EditorError expired = assertThrows(EditorError.class, () -> tokens.authenticate(bearer));

    assertEquals(clock.instant().minus(Duration.ofHours(13)).minus(Duration.ofHours(11)).plus(Duration.ofHours(12)),
        used.expires());
    assertEquals(EditorError.Code.TOKEN_EXPIRED, expired.code());
    assertEquals(List.of(pairing.tokenId()), tokens.expire());
    assertEquals(EditorError.Code.TOKEN_EXPIRED,
        assertThrows(EditorError.class, () -> tokens().authenticate(bearer)).code());
  }

  @Test
  void revokedTokensAndLinksAreRefusedAndTheStoreSurvivesARestart() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing kept = tokens.pair(grant(List.of("shop"), Duration.ofDays(7)));
    String keptBearer = tokens.exchange(kept.tokenId(), kept.secret(), Optional.empty()).getKey();
    EditorTokens.Pairing gone = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    String goneBearer = tokens.exchange(gone.tokenId(), gone.secret(), Optional.empty()).getKey();
    EditorTokens.Pairing link = tokens.pair(grant(List.of(), Duration.ofDays(7)));

    assertEquals(List.of(gone.tokenId()), tokens.revoke(gone.tokenId()));
    assertEquals(List.of(link.tokenId()), tokens.revoke(link.tokenId()));
    EditorTokens reloaded = tokens();

    assertEquals(EditorError.Code.TOKEN_REVOKED,
        assertThrows(EditorError.class, () -> reloaded.authenticate(goneBearer)).code());
    assertEquals(List.of("shop"), reloaded.authenticate(keptBearer).folders());
    assertTrue(reloaded.openPairings().isEmpty());
  }

  @Test
  void aDamagedStoreIsMovedAsideAndTheEditorStartsWithNoTokens() throws Exception {
    Path store = data.resolve(EditorTokens.PATH);
    Files.createDirectories(store.getParent());
    String torn = "{\"schemaVersion\":1,\"tokens\":[{\"id\":\"tok_";
    Files.writeString(store, torn);

    EditorTokens tokens = tokens();
    List<Path> aside;
    try (Stream<Path> files = Files.list(store.getParent())) {
      aside = files.filter((Path file) -> file.getFileName().toString().startsWith("tokens.damaged-")).toList();
    }
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    assertEquals(1, aside.size());
    assertEquals(torn, Files.readString(aside.getFirst()));
    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().contains("editor/" + aside.getFirst().getFileName()), warnings.getFirst());
    assertEquals(1, tokens().list().size());
  }

  @Test
  void aRevokeThatCannotBeSavedHoldsAndIsSavedOnceTheFolderTakesWritesAgain() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();
    Path folder = data.resolve(EditorTokens.PATH).getParent();
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeFalse(Files.isWritable(folder), "this user writes into read-only folders");
      assertThrows(IOException.class, () -> tokens.revoke(pairing.tokenId()));
      assertEquals(EditorError.Code.TOKEN_REVOKED,
          assertThrows(EditorError.class, () -> tokens.authenticate(bearer)).code());
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    tokens.expire();

    assertEquals(EditorError.Code.TOKEN_REVOKED,
        assertThrows(EditorError.class, () -> tokens().authenticate(bearer)).code());
  }

  @Test
  void aShortLifetimeSavesItsSlidExpiry() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofMinutes(20)));
    String bearer = tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty()).getKey();

    clock.advance(Duration.ofMinutes(6));
    EditorTokens.Token used = tokens.authenticate(bearer);

    assertEquals(used.expires(), tokens().find(pairing.tokenId()).orElseThrow().expires());
  }

  @Test
  void steadyUseSavesTheSlidExpiryAtLeastHourly() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
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
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    tokens.advance(pairing.tokenId(), 3L);
    EditorError replayed = assertThrows(EditorError.class, () -> tokens.advance(pairing.tokenId(), 3L));

    assertEquals(EditorError.Code.COUNTER_REPLAYED, replayed.code());
    assertEquals(3L, replayed.extra().get("counter").getAsLong());
    assertEquals(3L, tokens().find(pairing.tokenId()).orElseThrow().counter());
  }

  @Test
  void droppingPublishCommandsKeepsTheRest() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(new EditorTokens.Grant("", Optional.empty(),
        List.of(EditorScope.READ, EditorScope.PUBLISH_COMMANDS), List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    assertTrue(tokens.dropScope(pairing.tokenId(), EditorScope.PUBLISH_COMMANDS));
    assertEquals(List.of(EditorScope.READ), tokens.find(pairing.tokenId()).orElseThrow().scopes());
  }

  @Test
  void foldersReachThemselvesAndWhatIsInsideOnly() {
    EditorTokens.Token token = new EditorTokens.Token("tok_x", "h", "", Optional.empty(), List.of(EditorScope.READ),
        List.of("shop"), clock.instant(), clock.instant(), Duration.ofDays(1), Optional.empty(), 0L, Optional.empty(),
        clock.instant());
    EditorTokens.Token open = new EditorTokens.Token("tok_y", "h", "", Optional.empty(), List.of(EditorScope.READ),
        List.of(), clock.instant(), clock.instant(), Duration.ofDays(1), Optional.empty(), 0L, Optional.empty(),
        clock.instant());

    assertTrue(token.reaches("shop"));
    assertTrue(token.reaches("shop/sub"));
    assertFalse(token.reaches("shopping"));
    assertFalse(token.reaches(""));
    assertTrue(open.reaches(""));
    assertFalse(EditorTokens.validFolder("Shop"));
    assertFalse(EditorTokens.validFolder("shop/"));
    assertTrue(EditorTokens.validFolder("shop/sub-2"));
  }

  @Test
  void theWaitingLinkAndTokenLimitsAreEnforced() throws Exception {
    EditorTokens tokens = tokens();
    for (int index = 0; index < EditorTokens.MAXIMUM_PAIRINGS; index++) {
      tokens.pair(grant(List.of(), Duration.ofDays(7)));
    }

    IllegalStateException full = assertThrows(IllegalStateException.class,
        () -> tokens.pair(grant(List.of(), Duration.ofDays(7))));
    assertTrue(full.getMessage().startsWith("Too many pairing links are waiting"));
  }

  @Test
  void theStoreIsReadableJson() throws Exception {
    EditorTokens tokens = tokens();
    EditorTokens.Pairing pairing = tokens.pair(grant(List.of(), Duration.ofDays(7)));
    tokens.exchange(pairing.tokenId(), pairing.secret(), Optional.empty());

    JsonObject stored = JsonParser.parseString(Files.readString(data.resolve(EditorTokens.PATH))).getAsJsonObject();

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
