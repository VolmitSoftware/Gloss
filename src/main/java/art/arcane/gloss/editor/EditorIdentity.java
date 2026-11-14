package art.arcane.gloss.editor;

import art.arcane.gloss.doc.AtomicFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * The server's Ed25519 identity in {@code editor/identity.json}. The relay registers the server under
 * {@link #fingerprint()}; pairing codes and {@code GET /v1/hello} carry the same key and fingerprint.
 */
public final class EditorIdentity {
  public static final String RELATIVE_PATH = "editor/identity.json";
  private static final int VERSION = 1;
  private static final int KEY_BYTES = 32;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final byte[] seed;
  private final PrivateKey privateKey;
  private final String serverKey;
  private final String fingerprint;

  private EditorIdentity(byte[] seed, KeyPair pair) {
    this.seed = seed.clone();
    byte[] encoded = pair.getPublic().getEncoded();
    byte[] rawPublicKey = Arrays.copyOfRange(encoded, encoded.length - KEY_BYTES, encoded.length);
    this.privateKey = pair.getPrivate();
    this.serverKey = ENCODER.encodeToString(rawPublicKey);
    this.fingerprint = PairingCode.fingerprint(rawPublicKey);
  }

  /** The identity whose private key is the 32-byte Ed25519 seed. */
  public static EditorIdentity fromSeed(byte[] seed) {
    if (seed.length != KEY_BYTES) {
      throw new IllegalArgumentException("an Ed25519 seed is 32 bytes");
    }
    byte[] copy = seed.clone();
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
      generator.initialize(NamedParameterSpec.ED25519, new SeedRandom(copy));
      return new EditorIdentity(copy, generator.generateKeyPair());
    } catch (GeneralSecurityException missing) {
      throw new IllegalStateException("Ed25519 is unavailable", missing);
    }
  }

  public static EditorIdentity generate() {
    byte[] seed = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(seed);
    return fromSeed(seed);
  }

  /** Reads the identity under {@code data}, creating it on first use. Refuses a file whose key does not match its seed. */
  public static EditorIdentity loadOrCreate(Path data) throws IOException {
    Path path = data.resolve(RELATIVE_PATH);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      EditorIdentity created = generate();
      JsonObject document = new JsonObject();
      document.addProperty("v", VERSION);
      document.addProperty("seed", ENCODER.encodeToString(created.seed));
      document.addProperty("serverKey", created.serverKey);
      document.addProperty("fingerprint", created.fingerprint);
      AtomicFiles.replaceDurable(path, (document + "\n").getBytes(StandardCharsets.UTF_8));
      ownerOnly(path);
      return created;
    }
    try {
      return read(path);
    } catch (IOException | RuntimeException damaged) {
      String reason = damaged.getMessage() == null ? damaged.getClass().getSimpleName()
          : damaged.getMessage().replace(RELATIVE_PATH + ": ", "");
      throw new IOException(RELATIVE_PATH + " is damaged (" + reason + "); delete it and restart to create a new "
          + "editor identity, then pair the editor again", damaged);
    }
  }

  private static EditorIdentity read(Path path) throws IOException {
    JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
    if (!parsed.isJsonObject()) {
      throw new IOException(RELATIVE_PATH + ": expected one JSON object");
    }
    JsonObject document = parsed.getAsJsonObject();
    JsonElement version = document.get("v");
    JsonElement seedText = document.get("seed");
    JsonElement key = document.get("serverKey");
    if (version == null || !version.isJsonPrimitive() || version.getAsInt() != VERSION || seedText == null
        || !seedText.isJsonPrimitive() || key == null || !key.isJsonPrimitive()) {
      throw new IOException(RELATIVE_PATH + ": expected v, seed and serverKey");
    }
    byte[] seed;
    try {
      seed = DECODER.decode(seedText.getAsString());
    } catch (IllegalArgumentException invalid) {
      throw new IOException(RELATIVE_PATH + ": seed is not base64url", invalid);
    }
    if (seed.length != KEY_BYTES) {
      throw new IOException(RELATIVE_PATH + ": seed must be 32 bytes");
    }
    EditorIdentity identity = fromSeed(seed);
    if (!identity.serverKey.equals(key.getAsString())) {
      throw new IOException(RELATIVE_PATH + ": serverKey does not match seed");
    }
    return identity;
  }

  private static void ownerOnly(Path path) throws IOException {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    Files.setPosixFilePermissions(path, permissions);
  }

  /** Raw 32-byte public key in base64url without padding. */
  public String serverKey() {
    return serverKey;
  }

  /** {@code sha256:<hex>} of the raw public key. */
  public String fingerprint() {
    return fingerprint;
  }

  /** Ed25519 signature of the UTF-8 text, base64url without padding. */
  public String sign(String text) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(privateKey);
      signature.update(text.getBytes(StandardCharsets.UTF_8));
      return ENCODER.encodeToString(signature.sign());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Ed25519 signing failed", failure);
    }
  }

  /** Feeds the seed to the key generator, which draws exactly the 32 private-key bytes. */
  private static final class SeedRandom extends SecureRandom {
    private final byte[] seed;

    private SeedRandom(byte[] seed) {
      super();
      this.seed = seed;
    }

    @Override
    public void nextBytes(byte[] bytes) {
      if (bytes.length != seed.length) {
        throw new IllegalStateException("unexpected key generator draw of " + bytes.length + " bytes");
      }
      System.arraycopy(seed, 0, bytes, 0, bytes.length);
    }
  }
}
