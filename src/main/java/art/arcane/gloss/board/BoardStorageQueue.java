package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.StorageTaskRunner;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BoardStorageQueue {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30L;
    private static final long FORCE_SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final DocumentStore<BoardDoc> store;
    private final StorageTaskRunner taskRunner;
    private final Logger logger;
    private final AtomicBoolean accepting;

    BoardStorageQueue(DocumentStore<BoardDoc> store, StorageTaskRunner taskRunner, Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.taskRunner = Objects.requireNonNull(taskRunner, "taskRunner");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.accepting = new AtomicBoolean(true);
    }

    void save(String id, BoardDoc document) {
        submit("save", id, () -> store.write(id, document));
    }

    void delete(String id) {
        submit("delete", id, () -> store.delete(id));
    }

    void shutdown() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        taskRunner.shutdown();
        boolean interrupted = false;
        try {
            if (!taskRunner.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Timed out draining board storage operations; cancelling remaining work.");
                taskRunner.shutdownNow();
                if (!taskRunner.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warning("Board storage operations did not stop after forced cancellation.");
                }
            }
        } catch (InterruptedException interruption) {
            interrupted = true;
            taskRunner.shutdownNow();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(String operation, String id, StorageOperation action) {
        if (!accepting.get()) {
            logger.warning("Could not " + operation + " board " + id + ": board storage is shut down.");
            return;
        }
        try {
            taskRunner.submit(() -> execute(operation, id, action));
        } catch (RejectedExecutionException rejected) {
            logger.log(Level.WARNING, "Could not " + operation + " board " + id
                + ": board storage rejected the operation.", rejected);
        }
    }

    private void execute(String operation, String id, StorageOperation action) {
        try {
            action.run();
        } catch (IOException failure) {
            logger.log(Level.WARNING, "Unable to " + operation + " board file " + id + ".json", failure);
        }
    }

    @FunctionalInterface
    private interface StorageOperation {
        void run() throws IOException;
    }
}
