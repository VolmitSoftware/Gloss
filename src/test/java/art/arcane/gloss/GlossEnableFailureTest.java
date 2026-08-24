package art.arcane.gloss;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossEnableFailureTest {
    @Test
    void runtimeEnableFailureIsPropagatedUnchanged() {
        IllegalStateException failure = new IllegalStateException("startup failed");

        assertSame(failure, Gloss.propagateEnableFailure(failure));
    }

    @Test
    void checkedEnableFailureRetainsItsCause() {
        IOException failure = new IOException("startup failed");

        RuntimeException propagated = Gloss.propagateEnableFailure(failure);

        assertTrue(propagated instanceof IllegalStateException);
        assertSame(failure, propagated.getCause());
    }

    @Test
    void fatalEnableFailureIsPropagatedUnchanged() {
        AssertionError failure = new AssertionError("startup failed");

        AssertionError propagated = assertThrows(AssertionError.class,
            () -> Gloss.propagateEnableFailure(failure));

        assertSame(failure, propagated);
    }
}
