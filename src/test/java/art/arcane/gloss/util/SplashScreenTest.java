package art.arcane.gloss.util;

import art.arcane.gloss.editor.EditorSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SplashScreenTest {
    @Test
    void bannerNamesTheConfiguredEditor() {
        assertEquals("http://127.0.0.1:8800", SplashScreen.editorAddress("http://127.0.0.1:8800"));
        assertEquals("https://editor.example.net/gloss", SplashScreen.editorAddress("https://editor.example.net/gloss"));
    }

    @Test
    void bannerFallsBackToTheHostedEditor() {
        assertEquals(EditorSettings.EDITOR_URL, SplashScreen.editorAddress(null));
        assertEquals(EditorSettings.EDITOR_URL, SplashScreen.editorAddress("  "));
    }
}
