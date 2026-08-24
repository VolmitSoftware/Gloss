package art.arcane.gloss;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorLoggingPolicyTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java");
    private static final String DIRECT_PLUGIN_LOG = "getLogger().";

    @Test
    void productionSourcesDoNotBypassTheGlossLogger() throws IOException {
        List<Path> sources = javaSources();
        int rawConsoleMessages = 0;
        for (Path source : sources) {
            String text = Files.readString(source);
            assertFalse(text.contains("System.out"), source.toString());
            assertFalse(text.contains("System.err"), source.toString());
            assertFalse(text.contains("printStackTrace("), source.toString());
            assertFalse(text.contains("Bukkit.getLogger("), source.toString());
            assertFalse(text.contains("getServer().getLogger("), source.toString());
            if (!source.endsWith(Path.of("art/arcane/gloss/Gloss.java"))) {
                assertFalse(text.contains("Logger.getLogger("), source.toString());
                assertFalse(hasDirectPluginLogCall(text), source.toString());
            }
            int index = text.indexOf("Bukkit.getConsoleSender().sendMessage(");
            while (index >= 0) {
                rawConsoleMessages++;
                assertTrue(source.endsWith(Path.of("art/arcane/gloss/util/SplashScreen.java")), source.toString());
                index = text.indexOf("Bukkit.getConsoleSender().sendMessage(", index + 1);
            }
        }
        assertEquals(1, rawConsoleMessages);
        assertContains("art/arcane/gloss/Gloss.java", "Logger.getLogger(\"Gloss\")");
        assertContains("art/arcane/gloss/Gloss.java", "\"[Gloss] \" + message");
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
            Gloss.logExceptionStack(false, failure, "Late async failure.");
            assertEquals("[Gloss] Late async failure.", captured.get().getMessage());
            assertSame(failure, captured.get().getThrown());
        } finally {
            fallback.removeHandler(handler);
            fallback.setLevel(previousLevel);
            fallback.setUseParentHandlers(previousParentHandlers);
            Gloss.instance = previous;
        }
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }

    private static boolean hasDirectPluginLogCall(String source) {
        int index = source.indexOf(DIRECT_PLUGIN_LOG);
        while (index >= 0) {
            int methodStart = index + DIRECT_PLUGIN_LOG.length();
            if (source.startsWith("info(", methodStart)
                || source.startsWith("warning(", methodStart)
                || source.startsWith("severe(", methodStart)
                || source.startsWith("fine(", methodStart)
                || source.startsWith("log(", methodStart)) {
                return true;
            }
            index = source.indexOf(DIRECT_PLUGIN_LOG, methodStart);
        }
        return false;
    }

    private static void assertContains(String relativePath, String expected) throws IOException {
        Path source = MAIN_SOURCE.resolve(relativePath);
        assertTrue(Files.readString(source).contains(expected), source.toString());
    }
}
