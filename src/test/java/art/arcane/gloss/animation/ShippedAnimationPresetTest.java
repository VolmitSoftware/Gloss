package art.arcane.gloss.animation;

import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippedAnimationPresetTest {
  private static final Set<String> EFFECT_NAMES = Set.of(
      "marquee", "timeline", "typewriter", "flash", "wipe", "scanner", "decode", "odometer", "wave");

  @Test
  void shippedEffectsRenderAndAdvanceAtTickCadence() throws IOException {
    assertTrue(ShippedDocumentCatalog.ANIMATIONS.names().containsAll(EFFECT_NAMES));
    for (String name : EFFECT_NAMES) {
      AnimationDoc document = read(name);
      assertEquals(1, document.frames().size(), name);
      String frame = document.frames().getFirst();
      Set<String> samples = new HashSet<>();
      for (double seconds : List.of(0.0D, 0.25D, 0.5D)) {
        String rendered = render(frame, seconds);
        assertFalse(rendered.contains("{{"), name);
        assertFalse(rendered.contains("\n"), name);
        assertTrue(rendered.length() <= 32, name + " delivered " + rendered.length() + " units");
        samples.add(rendered);
      }
      assertTrue(samples.size() > 1, name + " stayed unchanged across tick samples");
    }
  }

  private static AnimationDoc read(String name) throws IOException {
    String resource = "/defaults/animations/" + name + ".json";
    try (InputStream stream = ShippedAnimationPresetTest.class.getResourceAsStream(resource)) {
      assertNotNull(stream, resource);
      return AnimationDoc.parse(name + ".json", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static String render(String frame, double seconds) {
    int open = frame.indexOf("{{");
    int close = frame.indexOf("}}", open + 2);
    assertTrue(open >= 0 && close > open, frame);
    String source = frame.substring(open + 2, close).trim();
    ExprScope scope = new ExprScope() {
      @Override
      public Object variable(String name) {
        return switch (name) {
          case "time.seconds" -> seconds;
          case "time.ms" -> seconds * 1000.0D;
          case "time.ticks" -> seconds * 20.0D;
          default -> null;
        };
      }

      @Override
      public Object call(String name, List<Object> args) {
        return ExprFunctions.call(name, args);
      }
    };
    return frame.substring(0, open)
        + ExprEvaluator.string(ExprParser.parse(source), scope)
        + frame.substring(close + 2);
  }
}
