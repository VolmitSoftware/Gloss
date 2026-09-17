package art.arcane.gloss.velocity;

import art.arcane.gloss.animation.AnimationMode;
import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

final class ProxyTextDocumentsTest {
    @TempDir
    Path directory;

    @Test
    void decodesEmojiValuesAndAppliesDocumentDefaults() throws IOException {
        write("emoji/heart.json", "{\"schemaVersion\":1,\"revision\":1,\"trigger\":\"<3\",\"emoji\":\"U+2764;\"}");
        write("emoji/alpha.json", "{\"schemaVersion\":1,\"revision\":1,\"emoji\":\"U+0041;U+0042;\",\"enabled\":false}");
        ProxyTextDocuments.Content content = ProxyTextDocuments.load(directory, settings(true, true));
        assertEquals(List.of("alpha", "heart"), content.emoji().stream().map(ProxyTextDocuments.Emoji::id).toList());
        ProxyTextDocuments.Emoji alpha = content.emoji().getFirst();
        assertEquals("AB", alpha.emoji());
        assertEquals("", alpha.trigger());
        assertFalse(alpha.enabled());
        ProxyTextDocuments.Emoji heart = content.emoji().get(1);
        assertEquals("❤", heart.emoji());
        assertEquals("<3", heart.trigger());
        assertTrue(heart.enabled());
        assertTrue(text().test(heart.show(), scope()));
    }

    @Test
    void buildsClipsFromModeAndClampedFrameInterval() throws IOException {
        write("animations/fast.json", "{\"schemaVersion\":1,\"revision\":1,\"mode\":\"ASCEND_DESCEND\","
            + "\"frameIntervalMs\":0,\"frames\":[\"a\",null]}");
        write("animations/slow.json", "{\"schemaVersion\":1,\"revision\":1,\"mode\":\"descend\","
            + "\"frameIntervalMs\":999999,\"frames\":[\"a\"],\"show\":\"viewer.present\"}");
        ProxyTextDocuments.Content content = ProxyTextDocuments.load(directory, settings(true, true));
        ProxyTextDocuments.Animation fast = content.animations().get("fast");
        assertEquals(AnimationMode.ASCEND_DESCEND, fast.clip().mode());
        assertEquals(1000.0D, fast.clip().targetFramerate());
        assertEquals(List.of("a", ""), fast.clip().frames());
        assertEquals("fast", fast.clip().id());
        ProxyTextDocuments.Animation slow = content.animations().get("slow");
        assertEquals(AnimationMode.DESCEND, slow.clip().mode());
        assertEquals(1000.0D / 60000.0D, slow.clip().targetFramerate());
        assertFalse(text().test(slow.show(), scope()));
    }

    @Test
    void ignoresDocumentsOnOtherSchemasAndNonJsonFiles() throws IOException {
        write("emoji/heart.json", "{\"schemaVersion\":2,\"revision\":1,\"emoji\":\"U+2764;\"}");
        write("emoji/notes.txt", "not a document");
        write("animations/marquee.json", "{\"schemaVersion\":9,\"revision\":1,\"mode\":\"ascend\","
            + "\"frameIntervalMs\":50,\"frames\":[\"a\"]}");
        ProxyTextDocuments.Content content = ProxyTextDocuments.load(directory, settings(true, true));
        assertTrue(content.emoji().isEmpty());
        assertTrue(content.animations().isEmpty());
    }

    @Test
    void rejectsEmojiAndAnimationDocumentsThatCannotRender() throws IOException {
        write("emoji/broken.json", "{\"schemaVersion\":1,\"revision\":1,\"emoji\":\"   \"}");
        assertThrows(IllegalArgumentException.class, () -> ProxyTextDocuments.load(directory, settings(true, true)));
        write("emoji/broken.json", "{\"schemaVersion\":1,\"revision\":1}");
        assertThrows(IllegalArgumentException.class, () -> ProxyTextDocuments.load(directory, settings(true, true)));
        write("emoji/broken.json", "{\"schemaVersion\":1,\"revision\":1,\"emoji\":\"x\",\"show\":\"viewer.world\"}");
        assertThrows(IllegalArgumentException.class, () -> ProxyTextDocuments.load(directory, settings(true, true)));
        Files.delete(directory.resolve("emoji/broken.json"));
        write("animations/broken.json", "{\"schemaVersion\":1,\"revision\":1,\"frameIntervalMs\":50,\"frames\":[\"a\"]}");
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> ProxyTextDocuments.load(directory, settings(true, true))).getMessage().contains("requires a mode"));
        write("animations/broken.json", "{\"schemaVersion\":1,\"revision\":1,\"mode\":\"spin\","
            + "\"frameIntervalMs\":50,\"frames\":[\"a\"]}");
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> ProxyTextDocuments.load(directory, settings(true, true))).getMessage().contains("unknown animation mode"));
        write("animations/broken.json", "{\"schemaVersion\":1,\"revision\":1,\"mode\":\"ascend\","
            + "\"frameIntervalMs\":50,\"frames\":[]}");
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> ProxyTextDocuments.load(directory, settings(true, true))).getMessage().contains("at least one frame"));
        write("animations/broken.json", "{\"schemaVersion\":1,\"revision\":1,\"mode\":\"ascend\","
            + "\"frameIntervalMs\":50,\"frames\":[\"{{ server.online\"]}");
        assertThrows(IllegalArgumentException.class, () -> ProxyTextDocuments.load(directory, settings(true, true)));
        write("animations/broken.json", "{\"schemaVersion\":1,\"revision\":1,\"mode\":\"ascend\","
            + "\"frameIntervalMs\":50,\"frames\":[\"{{ nothing.here }}\"]}");
        assertThrows(IllegalArgumentException.class, () -> ProxyTextDocuments.load(directory, settings(true, true)));
    }

    @Test
    void carriesTheEnabledSwitchesFromProxySettings() throws IOException {
        write("emoji/heart.json", "{\"schemaVersion\":1,\"revision\":1,\"emoji\":\"U+2764;\"}");
        ProxyTextDocuments.Content on = ProxyTextDocuments.load(directory, settings(true, true));
        assertTrue(on.emojiEnabled());
        assertTrue(on.animationsEnabled());
        ProxyTextDocuments.Content off = ProxyTextDocuments.load(directory, settings(false, false));
        assertFalse(off.emojiEnabled());
        assertFalse(off.animationsEnabled());
        assertEquals(1, off.emoji().size());
    }

    @Test
    void loadsEverySeededServerEditionDefault() throws IOException {
        ProxyDocuments.seed(directory);
        ProxyTextDocuments.Content content = ProxyTextDocuments.load(directory, settings(true, true));
        assertEquals(count("emoji"), content.emoji().size());
        assertEquals(count("animations"), content.animations().size());
        assertTrue(content.emoji().stream().anyMatch(entry -> entry.id().equals("heart")));
        assertEquals("❤", content.emoji().stream()
            .filter(entry -> entry.id().equals("heart")).findFirst().orElseThrow().emoji());
        ProxyTextDocuments.Animation marquee = content.animations().get("marquee");
        assertEquals(AnimationMode.ASCEND, marquee.clip().mode());
        assertEquals(1.0D, marquee.clip().targetFramerate());
        assertEquals(60, content.animations().get("rainbow").clip().frames().size());
    }

    private static ProxyDocuments.Settings settings(boolean emoji, boolean animations) {
        return new ProxyDocuments.Settings(true, true, true, true, true, emoji, animations, 500L, true);
    }

    private static ProxyText text() {
        return new ProxyText(mock(ProxyServer.class));
    }

    private static ExpressionScope scope() {
        return text().scope(null, null);
    }

    private long count(String folder) throws IOException {
        try (Stream<Path> paths = Files.list(directory.resolve(folder))) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    private void write(String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
