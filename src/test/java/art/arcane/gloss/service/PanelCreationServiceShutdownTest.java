package art.arcane.gloss.service;

import art.arcane.gloss.config.menu.MenuDocument;
import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelCreationServiceShutdownTest {
    @TempDir
    Path folder;

    @Test
    void shutdownForceCancelsAfterTwoBoundedWaits() {
        NeverTerminatingExecutor executor = new NeverTerminatingExecutor();
        PanelCreationService service = new PanelCreationService(dependencies(), executor);

        service.shutdown();

        assertTrue(executor.shutdown);
        assertTrue(executor.shutdownNow);
        assertEquals(2, executor.awaitCalls);
    }

    private PanelCreationService.Dependencies dependencies() {
        Logger logger = Logger.getLogger(PanelCreationServiceShutdownTest.class.getName());
        logger.setLevel(Level.OFF);
        return new PanelCreationService.Dependencies(
            folder,
            logger,
            new GlossPersistenceCoordinator(),
            new GlossProjectTransaction(folder),
            new EmptyMenuPublication(),
            new EmptyPanelPublication()
        );
    }

    private static final class EmptyMenuPublication implements PanelCreationService.MenuPublication {
        @Override
        public boolean exists(String id) {
            return false;
        }

        @Override
        public MenuDocument publish(String id, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MenuDocument recover(MenuDocument created) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyPanelPublication implements PanelCreationService.PanelPublication {
        @Override
        public Optional<PanelDefinition> current(String id) {
            return Optional.empty();
        }

        @Override
        public PanelDefinition publish(PanelDefinition created) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PanelDefinition recover(PanelDefinition created) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NeverTerminatingExecutor extends AbstractExecutorService {
        private boolean shutdown;
        private boolean shutdownNow;
        private int awaitCalls;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            shutdownNow = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            awaitCalls++;
            return false;
        }

        @Override
        public void execute(Runnable command) {
        }
    }
}
