package art.arcane.gloss.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingCodeTest {
  @TempDir
  Path data;

  private static final Path FIXTURES = Path.of("schema/editor/fixtures");

  @Test
  void aCodeRoundTripsWithItsFieldsInOrder() {
    EditorIdentity identity = EditorIdentity.generate();
    PairingCode code = new PairingCode(Optional.of("http://127.0.0.1:9799"), Optional.of("wss://relay.example.com"),
        identity.fingerprint(), identity.serverKey(), "tok_Ab3dEf6hIj9kLm2n", "A".repeat(43),
        Instant.parse("2026-11-13T10:10:00Z"), Optional.of("laptop"), Optional.of("Twin Peaks"),
        List.of("read", "publish:world"), List.of("shop"));

    String encoded = code.encode();
    PairingCode decoded = PairingCode.decode(encoded);

    assertTrue(encoded.startsWith("GLS1."));
    assertEquals(code, decoded);
    assertEquals(encoded, decoded.encode());
    assertEquals(List.of("v", "directUrl", "relayUrl", "fingerprint", "serverKey", "tokenId", "secret", "expiresAt",
        "label", "serverName", "scopes", "folders"), List.copyOf(code.payload().keySet()));
  }

  @Test
  void aCodeRefusesPlainHttpOffLoopbackAndAMismatchedFingerprint() {
    EditorIdentity identity = EditorIdentity.generate();
    EditorIdentity other = EditorIdentity.generate();

    assertThrows(IllegalArgumentException.class, () -> new PairingCode(Optional.of("http://example.com:9799"),
        Optional.empty(), identity.fingerprint(), identity.serverKey(), "tok_Ab3dEf6hIj9kLm2n", "A".repeat(43),
        Instant.now(), Optional.empty(), Optional.empty(), List.of("read"), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new PairingCode(Optional.empty(), Optional.of("ws://example.com"),
        identity.fingerprint(), identity.serverKey(), "tok_Ab3dEf6hIj9kLm2n", "A".repeat(43), Instant.now(),
        Optional.empty(), Optional.empty(), List.of("read"), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new PairingCode(Optional.of("https://example.com"),
        Optional.empty(), other.fingerprint(), identity.serverKey(), "tok_Ab3dEf6hIj9kLm2n", "A".repeat(43),
        Instant.now(), Optional.empty(), Optional.empty(), List.of("read"), List.of()));
    assertThrows(IllegalArgumentException.class, () -> PairingCode.decode("ANM1.e30"));
  }

  @Test
  void theIdentityPersistsAndADamagedFileNamesTheFix() throws Exception {
    EditorIdentity created = EditorIdentity.loadOrCreate(data);
    EditorIdentity loaded = EditorIdentity.loadOrCreate(data);
    Files.writeString(data.resolve(EditorIdentity.RELATIVE_PATH), "{\"v\":1}");

    java.io.IOException damaged = assertThrows(java.io.IOException.class, () -> EditorIdentity.loadOrCreate(data));

    assertEquals(created.fingerprint(), loaded.fingerprint());
    assertTrue(damaged.getMessage().startsWith("editor/identity.json is damaged"));
    assertTrue(damaged.getMessage().endsWith("then pair the editor again"));
  }

  @Test
  void theRelayRegistrationMatchesTheSharedGolden() throws Exception {
    JsonObject golden = JsonParser.parseString(Files.readString(FIXTURES.resolve("relay-handshake.json"),
        StandardCharsets.UTF_8)).getAsJsonObject();
    byte[] seed = new byte[32];
    for (int index = 0; index < 32; index++) {
      seed[index] = golden.getAsJsonArray("seedBytes").get(index).getAsByte();
    }
    EditorIdentity identity = EditorIdentity.fromSeed(seed);

    assertEquals(golden.get("serverKey").getAsString(), identity.serverKey());
    assertEquals(golden.get("fingerprint").getAsString(), identity.fingerprint());
    assertEquals(golden.get("signature").getAsString(),
        identity.sign(art.arcane.gloss.editor.relay.EditorRelayClient.CHALLENGE_PREFIX + golden.get("nonce").getAsString()));
  }

  @Test
  void theProofAnEditorChecksVerifiesWithThePairedKey() throws Exception {
    EditorIdentity identity = EditorIdentity.generate();
    String challenge = "B".repeat(43);
    byte[] signature = Base64.getUrlDecoder().decode(identity.sign(
        art.arcane.gloss.editor.relay.EditorRelayClient.PROOF_PREFIX + challenge));
    byte[] raw = Base64.getUrlDecoder().decode(identity.serverKey());
    byte[] spki = HexFormat.of().parseHex("302a300506032b6570032100");
    byte[] encoded = new byte[spki.length + raw.length];
    System.arraycopy(spki, 0, encoded, 0, spki.length);
    System.arraycopy(raw, 0, encoded, spki.length, raw.length);
    PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(("gloss-editor-proof/1 " + challenge).getBytes(StandardCharsets.UTF_8));

    assertTrue(verifier.verify(signature));
    assertEquals(identity.fingerprint(), PairingCode.fingerprint(raw));
  }
}
