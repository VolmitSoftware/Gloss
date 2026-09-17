package art.arcane.gloss.proxy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

public final class OwnershipProtocol {
    public static final String CHANNEL = "gloss:ownership";
    public static final int MOTD = 1;
    public static final int TABLIST = 2;
    public static final int SCOREBOARD = 4;
    public static final int SURFACES = 8;
    public static final int CONNECTIONS = 16;

    private static final int MAGIC = 0x474C4F53;
    private static final byte VERSION = 1;
    private static final byte REQUEST = 1;
    private static final byte REPLY = 2;
    private static final int NONCE_LENGTH = 16;
    private static final int REQUEST_LENGTH = 38;
    private static final int PAYLOAD_LENGTH = 50;
    private static final int REPLY_LENGTH = 82;
    private static final int ALL_FEATURES = MOTD | TABLIST | SCOREBOARD | SURFACES | CONNECTIONS;
    private static final byte[] DOMAIN = "Gloss proxy ownership v1\0".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private OwnershipProtocol() {
    }

    public static Request createRequest(UUID playerId) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        return new Request(playerId, nonce);
    }

    public static byte[] encodeRequest(Request request) {
        ByteBuffer buffer = ByteBuffer.allocate(REQUEST_LENGTH);
        writeRequest(buffer, request, REQUEST);
        return buffer.array();
    }

    public static Request decodeRequest(byte[] data) {
        if (data == null || data.length != REQUEST_LENGTH) {
            throw new IllegalArgumentException("Invalid ownership request length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (!readHeader(buffer, REQUEST)) {
            throw new IllegalArgumentException("Invalid ownership request header");
        }
        UUID playerId = new UUID(buffer.getLong(), buffer.getLong());
        byte[] nonce = new byte[NONCE_LENGTH];
        buffer.get(nonce);
        return new Request(playerId, nonce);
    }

    public static byte[] reply(Request request, int mask, long expiresAtMillis, byte[] key) {
        if (!validMask(mask)) {
            throw new IllegalArgumentException("Invalid proxy ownership feature mask");
        }
        ByteBuffer buffer = ByteBuffer.allocate(REPLY_LENGTH);
        writeRequest(buffer, request, REPLY);
        buffer.putInt(mask).putLong(expiresAtMillis);
        buffer.put(sign(buffer.array(), key));
        return buffer.array();
    }

    public static OptionalInt verifyReply(byte[] data, Request request, byte[] key, long nowMillis) {
        if (data == null || data.length != REPLY_LENGTH || request == null || key == null || key.length == 0) {
            return OptionalInt.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (!readHeader(buffer, REPLY)) {
            return OptionalInt.empty();
        }
        byte[] identity = new byte[32];
        buffer.get(identity);
        ByteBuffer expectedIdentity = ByteBuffer.allocate(32);
        expectedIdentity.putLong(request.playerId().getMostSignificantBits());
        expectedIdentity.putLong(request.playerId().getLeastSignificantBits());
        expectedIdentity.put(request.nonce());
        int mask = buffer.getInt();
        long expiresAtMillis = buffer.getLong();
        long remainingMillis = expiresAtMillis - nowMillis;
        if (!validMask(mask) || expiresAtMillis <= nowMillis || remainingMillis <= 0 || remainingMillis > 30_000L) {
            return OptionalInt.empty();
        }
        byte[] signature = Arrays.copyOfRange(data, PAYLOAD_LENGTH, REPLY_LENGTH);
        boolean identityMatches = MessageDigest.isEqual(identity, expectedIdentity.array());
        boolean signatureMatches = MessageDigest.isEqual(signature, sign(data, key));
        return identityMatches && signatureMatches ? OptionalInt.of(mask) : OptionalInt.empty();
    }

    private static void writeRequest(ByteBuffer buffer, Request request, byte type) {
        buffer.putInt(MAGIC).put(VERSION).put(type);
        buffer.putLong(request.playerId().getMostSignificantBits());
        buffer.putLong(request.playerId().getLeastSignificantBits());
        buffer.put(request.nonce());
    }

    private static boolean readHeader(ByteBuffer buffer, byte type) {
        return buffer.getInt() == MAGIC && buffer.get() == VERSION && buffer.get() == type;
    }

    private static boolean validMask(int mask) {
        return (mask & ~ALL_FEATURES) == 0;
    }

    private static byte[] sign(byte[] data, byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Proxy ownership signing key must not be empty");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(DOMAIN);
            mac.update(data, 0, PAYLOAD_LENGTH);
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot authenticate proxy ownership", exception);
        }
    }

    public record Request(UUID playerId, byte[] nonce) {
        public Request {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(nonce, "nonce");
            if (nonce.length != NONCE_LENGTH) {
                throw new IllegalArgumentException("Ownership challenge must contain 16 bytes");
            }
            nonce = nonce.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
