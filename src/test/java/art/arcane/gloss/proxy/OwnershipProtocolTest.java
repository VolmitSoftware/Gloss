package art.arcane.gloss.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipProtocolTest {
    private static final byte[] KEY = "test-forwarding-secret".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void requestRoundTripsAndProtectsNonceFromMutation() {
        UUID playerId = UUID.randomUUID();
        byte[] nonce = new byte[16];
        OwnershipProtocol.Request request = new OwnershipProtocol.Request(playerId, nonce);
        nonce[0] = 1;
        request.nonce()[1] = 2;
        OwnershipProtocol.Request decoded = OwnershipProtocol.decodeRequest(OwnershipProtocol.encodeRequest(request));
        assertEquals(playerId, decoded.playerId());
        assertArrayEquals(new byte[16], decoded.nonce());
        assertFalse(Arrays.equals(OwnershipProtocol.createRequest(playerId).nonce(), OwnershipProtocol.createRequest(playerId).nonce()));
    }

    @Test
    void verifiesEveryFeatureCombinationIncludingRelease() {
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(UUID.randomUUID());
        for (int mask = 0; mask <= 7; mask++) {
            byte[] response = OwnershipProtocol.reply(request, mask, NOW + 30_000L, KEY);
            assertEquals(OptionalInt.of(mask), OwnershipProtocol.verifyReply(response, request, KEY, NOW));
        }
    }

    @Test
    void rejectsModifiedResponsesAndWrongKeys() {
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(UUID.randomUUID());
        byte[] response = OwnershipProtocol.reply(request, 7, NOW + 10_000L, KEY);
        for (int index = 0; index < response.length; index++) {
            byte[] modified = response.clone();
            modified[index] ^= 1;
            assertTrue(OwnershipProtocol.verifyReply(modified, request, KEY, NOW).isEmpty(), "Byte " + index);
        }
        assertTrue(OwnershipProtocol.verifyReply(response, request, new byte[] {1}, NOW).isEmpty());
        assertTrue(OwnershipProtocol.verifyReply(response, request, new byte[0], NOW).isEmpty());
        assertTrue(OwnershipProtocol.verifyReply(response, request, null, NOW).isEmpty());
    }

    @Test
    void rejectsResponsesForOtherPlayersOrChallenges() {
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(UUID.randomUUID());
        byte[] response = OwnershipProtocol.reply(request, 7, NOW + 10_000L, KEY);
        OwnershipProtocol.Request otherPlayer = new OwnershipProtocol.Request(UUID.randomUUID(), request.nonce());
        OwnershipProtocol.Request nextChallenge = OwnershipProtocol.createRequest(request.playerId());
        assertTrue(OwnershipProtocol.verifyReply(response, otherPlayer, KEY, NOW).isEmpty());
        assertTrue(OwnershipProtocol.verifyReply(response, nextChallenge, KEY, NOW).isEmpty());
    }

    @Test
    void rejectsExpiredAndExcessiveLeasesIncludingOverflow() {
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(UUID.randomUUID());
        for (long expiration : new long[] {NOW - 1, NOW, NOW + 30_001L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            byte[] response = OwnershipProtocol.reply(request, 7, expiration, KEY);
            assertTrue(OwnershipProtocol.verifyReply(response, request, KEY, NOW).isEmpty());
        }
        byte[] response = OwnershipProtocol.reply(request, 7, Long.MAX_VALUE, KEY);
        assertTrue(OwnershipProtocol.verifyReply(response, request, KEY, Long.MIN_VALUE).isEmpty());
        assertEquals(OptionalInt.of(7), OwnershipProtocol.verifyReply(response, request, KEY, Long.MAX_VALUE - 1));
    }

    @Test
    void rejectsMalformedRequestsAndResponses() {
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(UUID.randomUUID());
        byte[] encodedRequest = OwnershipProtocol.encodeRequest(request);
        byte[] response = OwnershipProtocol.reply(request, 7, NOW + 10_000L, KEY);
        for (int length : new int[] {0, 1, 37, 39, 81, 83, 1024}) {
            byte[] malformed = new byte[length];
            assertThrows(IllegalArgumentException.class, () -> OwnershipProtocol.decodeRequest(malformed));
            assertTrue(OwnershipProtocol.verifyReply(malformed, request, KEY, NOW).isEmpty());
        }
        for (int index = 0; index < 6; index++) {
            byte[] modified = encodedRequest.clone();
            modified[index] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> OwnershipProtocol.decodeRequest(modified));
        }
        assertThrows(IllegalArgumentException.class, () -> OwnershipProtocol.decodeRequest(null));
        assertThrows(IllegalArgumentException.class, () -> OwnershipProtocol.decodeRequest(response));
        assertTrue(OwnershipProtocol.verifyReply(encodedRequest, request, KEY, NOW).isEmpty());
        assertTrue(OwnershipProtocol.verifyReply(null, request, KEY, NOW).isEmpty());
        assertTrue(OwnershipProtocol.verifyReply(response, null, KEY, NOW).isEmpty());
    }

    @Test
    void rejectsInvalidProducerArguments() {
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> OwnershipProtocol.reply(request, 64, NOW + 1, KEY));
        assertThrows(IllegalArgumentException.class, () -> OwnershipProtocol.reply(request, -1, NOW + 1, KEY));
        assertThrows(IllegalArgumentException.class, () -> OwnershipProtocol.reply(request, 7, NOW + 1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new OwnershipProtocol.Request(UUID.randomUUID(), new byte[15]));
    }
}
