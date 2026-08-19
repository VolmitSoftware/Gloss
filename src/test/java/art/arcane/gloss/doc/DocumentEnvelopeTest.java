package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentEnvelopeTest {
    @Test
    void matchingSchemaVersionPassesThrough() {
        assertEquals(1, DocumentEnvelope.requireSchemaVersion("emoji", 1, 1));
    }

    @Test
    void mismatchedSchemaVersionIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> DocumentEnvelope.requireSchemaVersion("emoji", 2, 1));
        assertEquals("unsupported emoji schemaVersion: 2", failure.getMessage());
    }

    @Test
    void missingSchemaVersionIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> DocumentEnvelope.requireSchemaVersion("board", 0, 1));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertEquals(1L, DocumentEnvelope.requireRevision("board", 1L));
        assertEquals(DocumentEnvelope.MAX_SAFE_REVISION,
            DocumentEnvelope.requireRevision("board", DocumentEnvelope.MAX_SAFE_REVISION));
        assertThrows(IllegalArgumentException.class, () -> DocumentEnvelope.requireRevision("board", 0L));
        assertThrows(IllegalArgumentException.class, () -> DocumentEnvelope.requireRevision("board", -5L));
        assertThrows(IllegalArgumentException.class,
            () -> DocumentEnvelope.requireRevision("board", DocumentEnvelope.MAX_SAFE_REVISION + 1L));
    }

    @Test
    void maxSafeRevisionMatchesJavascriptMaxSafeInteger() {
        assertEquals(9_007_199_254_740_991L, DocumentEnvelope.MAX_SAFE_REVISION);
    }
}
