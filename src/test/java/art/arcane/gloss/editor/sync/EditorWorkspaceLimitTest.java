package art.arcane.gloss.editor.sync;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A publish over the size limit names the setting the owner can change, with the value they set. */
public class EditorWorkspaceLimitTest {
  private static final int MIB = 1024 * 1024;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void aPublishOverTheLimitNamesTheEditorKeyAndTheSetting() throws IOException {
    Path data = folder.newFolder("data").toPath();
    Path images = Files.createDirectories(data.resolve("images"));
    for (int index = 0; index < 5; index++) {
      Files.write(images.resolve("noise-" + index + ".png"), noise(index));
    }
    EditorWorkspace workspace = EditorWorkspaceFixtures.detached(data, MIB);

    EditorWorkspace.Outcome outcome = workspace.publish("chg_limit", new EditorSyncChangeset(List.of(
        new EditorSyncChangeset.DocumentChange(false, "menu", "shop", null, "{\"components\":[]}")),
        List.of()), Map.of());

    assertEquals("rejected", outcome.status());
    assertTrue(outcome.message(), outcome.message().contains("[editor] maxProjectMiB"));
    assertTrue(outcome.message(), outcome.message().contains("(1 MiB)"));
    assertTrue(outcome.message(), outcome.message().contains("gloss.toml"));
    assertFalse(outcome.message(), outcome.message().contains("[editor.sync]"));
  }

  @Test
  public void theExceptionNamesTheEditorKeyAndNotAnInternalCeiling() {
    String message = new EditorSyncProjectTooLargeException(90L * MIB, 160L * MIB).getMessage();

    assertTrue(message, message.contains("[editor] maxProjectMiB"));
    assertFalse(message, message.contains("[editor.sync]"));
    assertFalse(message, message.contains("160"));
  }

  /** A 300 by 300 noise picture: about 270 KB as PNG, so five pass 1 MiB. */
  private static byte[] noise(int seed) throws IOException {
    BufferedImage image = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
    Random random = new Random(seed);
    for (int y = 0; y < 300; y++) {
      for (int x = 0; x < 300; x++) {
        image.setRGB(x, y, random.nextInt(0x1000000));
      }
    }
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", output);
      return output.toByteArray();
    }
  }
}
