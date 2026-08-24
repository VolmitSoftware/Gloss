package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.ExecutorStorageTaskRunner;
import art.arcane.gloss.doc.StorageTaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardStorageQueueTest {
    private static final DocumentReviser<BoardDoc> REVISER = new DocumentReviser<>() {
        @Override
        public long revisionOf(BoardDoc value) {
            return value.revision();
        }

        @Override
        public BoardDoc withRevision(BoardDoc value, long revision) {
            return value.withRevision(revision);
        }
    };

    @TempDir
    File folder;

    @Test
    void savesAndDeletesCompleteInSubmissionOrderBeforeShutdownReturns() throws Exception {
        DocumentStore<BoardDoc> store = new DocumentStore<>(BoardDoc.KIND, folder, REVISER);
        BoardStorageQueue storage = new BoardStorageQueue(store,
            new ExecutorStorageTaskRunner(getClass().getClassLoader(), "Gloss-Board-Storage-Test"), logger());

        storage.save("alpha", document(1L, "first"));
        storage.save("alpha", document(2L, "second"));
        storage.save("beta", document(1L, "temporary"));
        storage.delete("beta");
        storage.shutdown();

        BoardDoc alpha = BoardDoc.parse("alpha.json", Files.readString(new File(folder, "alpha.json").toPath()));
        assertEquals(2L, alpha.revision());
        assertEquals("second", alpha.title());
        assertFalse(new File(folder, "beta.json").exists());
    }

    @Test
    void shutdownForceCancelsAfterTwoBoundedWaits() {
        NeverTerminatingRunner runner = new NeverTerminatingRunner();
        DocumentStore<BoardDoc> store = new DocumentStore<>(BoardDoc.KIND, folder, REVISER);
        BoardStorageQueue storage = new BoardStorageQueue(store, runner, logger());

        storage.shutdown();

        assertTrue(runner.shutdown);
        assertTrue(runner.shutdownNow);
        assertEquals(2, runner.awaitCalls);
    }

    @Test
    void unsafeIdsAreRejectedBeforePublication() {
        assertEquals("my-board", BoardService.requireSafeId(BoardService.normalizeId(" my board ")));
        assertThrows(IllegalArgumentException.class, () -> BoardService.requireSafeId(""));
        assertThrows(IllegalArgumentException.class, () -> BoardService.requireSafeId("../board"));
        assertThrows(IllegalArgumentException.class, () -> BoardService.requireSafeId("folder/board"));
        assertThrows(IllegalArgumentException.class, () -> BoardService.requireSafeId("folder\\board"));
    }

    private static BoardDoc document(long revision, String title) {
        return new BoardDoc(BoardDoc.CURRENT_SCHEMA_VERSION, revision, title, List.of(), false, false,
            GlossBoardMeta.UNRESTRICTED_PERMISSION, List.of());
    }

    private static Logger logger() {
        Logger logger = Logger.getLogger(BoardStorageQueueTest.class.getName());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static final class NeverTerminatingRunner implements StorageTaskRunner {
        private boolean shutdown;
        private boolean shutdownNow;
        private int awaitCalls;

        @Override
        public StorageTaskHandle submit(Runnable task) {
            return () -> {
            };
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public void shutdownNow() {
            shutdownNow = true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            awaitCalls++;
            return false;
        }
    }
}
