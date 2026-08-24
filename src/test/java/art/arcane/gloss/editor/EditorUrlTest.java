package art.arcane.gloss.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class EditorUrlTest {
  @Test
  public void normalizesTheHostedEditorBase() {
    assertEquals("https://example.com/editor/",
        EditorUrl.base("HTTPS://EXAMPLE.COM/editor?ignored=true#fragment"));
    assertEquals("http://localhost:8080/", EditorUrl.base("http://localhost:8080"));
  }

  @Test
  public void rejectsNonHttpAndCredentialedUrls() {
    assertThrows(IllegalArgumentException.class, () -> EditorUrl.base("file:///tmp/editor"));
    assertThrows(IllegalArgumentException.class,
        () -> EditorUrl.base("https://operator@example.com/editor"));
    assertThrows(IllegalArgumentException.class, () -> EditorUrl.base("not a URL"));
  }
}
