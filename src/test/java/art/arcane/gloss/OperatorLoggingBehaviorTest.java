package art.arcane.gloss;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorLoggingBehaviorTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java");

    @Test
    void pluginSinksStayWiredToTheGlossLogger() throws IOException {
        assertContains("art/arcane/gloss/Gloss.java", "Logger.getLogger(\"Gloss\")");
        assertContains("art/arcane/gloss/Gloss.java", "ComponentLog.logLegacy(");
        assertContains("art/arcane/gloss/util/SplashScreen.java", "Gloss.log(Level.INFO, splash.toString())");
        assertFalse(Files.readString(MAIN_SOURCE.resolve(
            "art/arcane/gloss/menu/action/MessageMenuAction.java")).contains("LegacyComponentSerializer"));
    }

    @Test
    void highFrequencyFailuresUseBoundedLogging() throws IOException {
        assertContains("art/arcane/gloss/hologram/HologramAnimator.java", "logExceptionStackThrottled");
        assertContains("art/arcane/gloss/hologram/HologramAnimator.java", "Gloss.verbose(");
        assertContains("art/arcane/gloss/hologram/HologramService.java", "\"hologram-viewer-refresh\"");
        assertContains("art/arcane/gloss/tab/TablistService.java", "\"tablist-player-refresh\"");
        assertContains("art/arcane/gloss/chat/ChatListener.java", "\"chat-hook\"");
        assertContains("art/arcane/gloss/panel/PanelRuntimeManager.java", "\"panel-viewer-update\"");
    }

    @Test
    void fallbackLoggerKeepsBrandAndThrowableOutsideThePluginLifecycle() {
        Logger fallback = Logger.getLogger("Gloss");
        boolean previousParentHandlers = fallback.getUseParentHandlers();
        Level previousLevel = fallback.getLevel();
        AtomicReference<LogRecord> captured = new AtomicReference<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.set(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Throwable failure = new IllegalStateException("late callback");
        Gloss previous = Gloss.instance;
        Gloss.instance = null;
        fallback.setUseParentHandlers(false);
        fallback.setLevel(Level.ALL);
        fallback.addHandler(handler);
        try {
            Gloss.logExceptionStack(false, failure, "§cLate async failure.");
            assertEquals("[Gloss] Late async failure.", captured.get().getMessage());
            assertSame(failure, captured.get().getThrown());
        } finally {
            fallback.removeHandler(handler);
            fallback.setLevel(previousLevel);
            fallback.setUseParentHandlers(previousParentHandlers);
            Gloss.instance = previous;
        }
    }

    private static void assertContains(String relativePath, String expected) throws IOException {
        Path source = MAIN_SOURCE.resolve(relativePath);
        assertTrue(Files.readString(source).contains(expected), source.toString());
    }
}
