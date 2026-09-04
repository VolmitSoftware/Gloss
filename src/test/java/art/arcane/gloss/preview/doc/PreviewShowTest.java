package art.arcane.gloss.preview.doc;

import art.arcane.gloss.preview.PreviewElement;
import org.bukkit.Material;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class PreviewShowTest {
  @Test
  public void worldShowUsesTargetDaytimeAndReturnsAfterNight() {
    CompiledPreviewDocument document = parse("""
        {"show":"world.name == 'survival' && world.time < 12000",
         "elements":[{"type":"label","text":"'Daytime'"}]}
        """);
    PreviewFakes.FurnaceFake furnace = PreviewFakes.furnace()
        .worldName("survival").worldTime(6000).gameTime(100000);
    PreviewStateContext context = PreviewStateContext.forBlock(furnace.build(), PreviewFakes.player().build(), Map.of());
    assertEquals(1, document.build(context).size());
    assertEquals(6000.0D, (Double) context.variable("world.time"), 0.0D);
    assertEquals(1, furnace.calls("getTime"));
    furnace.worldTime(18000).gameTime(100001);
    assertTrue(document.build(context).isEmpty());
    furnace.worldTime(1000).gameTime(100002);
    assertEquals(1, document.build(context).size());
    furnace.worldName("creative").gameTime(100003);
    assertTrue(document.build(context).isEmpty());
  }

  @Test
  public void inventoryAndLockedContextsUseTheirExplicitTargetWorld() {
    PreviewFakes.FurnaceFake target = PreviewFakes.furnace().worldName("survival").worldTime(7000);
    PreviewStateContext inventory = PreviewStateContext.forWorld(target.world(), null,
        PreviewFakes.inventory(27).build(), Map.of());
    PreviewStateContext locked = PreviewStateContext.forWorld(target.world(), null, null, Map.of());
    assertEquals("survival", inventory.variable("world.name"));
    assertEquals(7000.0D, (Double) inventory.variable("world.time"), 0.0D);
    assertEquals("survival", locked.variable("world.name"));
    assertEquals(7000.0D, (Double) locked.variable("world.time"), 0.0D);
    assertEquals("", PreviewStateContext.statics(Map.of()).variable("world.name"));
    assertThrows(PreviewDocumentException.class, () -> parse("{\"show\":\"world.missing == 0\"}"));
  }

  @Test
  public void absentShowDefaultsToTrueAtEveryLevel() {
    CompiledPreviewDocument document = parse("""
        {"card":{"title":"'Title'"},"elements":[{"type":"label","text":"'Body'"}]}
        """);
    assertEquals(5, document.build(PreviewStateContext.statics(Map.of())).size());
    assertFalse(document.hasDynamicVisibility());
  }

  @Test
  public void falseDocumentHidesCardTitleAndContentBeforeEvaluatingThem() {
    CompiledPreviewDocument document = parse("""
        {"show":false,"card":{"title":"missing.title"},
         "elements":[{"type":"label","text":"missing.text"}]}
        """);
    List<String> errors = new ArrayList<>();
    assertTrue(document.build(PreviewStateContext.statics(Map.of()), errors::add).isEmpty());
    assertTrue(errors.isEmpty());
    assertFalse(document.visibility(PreviewStateContext.statics(Map.of())).shown());
  }

  @Test
  public void cardShowHidesOnlyChromeAndTitle() {
    CompiledPreviewDocument document = parse("""
        {"card":{"show":false,"title":"missing.title"},
         "elements":[{"type":"label","text":"'Body'"}]}
        """);
    List<String> errors = new ArrayList<>();
    List<PreviewElement> elements = document.build(PreviewStateContext.statics(Map.of()), errors::add);
    assertEquals(1, elements.size());
    assertTrue(elements.getFirst() instanceof PreviewElement.Label);
    assertTrue(errors.isEmpty());
  }

  @Test
  public void cardShowCannotOverrideFramedFalse() {
    CompiledPreviewDocument document = parse("""
        {"card":{"show":true,"framed":false,"title":"'Title'"},
         "elements":[{"type":"label","text":"'Body'"}]}
        """);
    assertEquals(1, document.build(PreviewStateContext.statics(Map.of())).size());
  }

  @Test
  public void failedElementAndCardShowsLeaveOtherElementsVisible() {
    CompiledPreviewDocument document = parse("""
        {"card":{"show":"custom.card","title":"'Title'"},"elements":[
          {"type":"label","show":"custom.element","text":"'Hidden'"},
          {"type":"label","text":"'Body'"}
        ]}
        """);
    List<String> errors = new ArrayList<>();
    assertEquals(1, document.build(PreviewStateContext.statics(Map.of()), errors::add).size());
    assertEquals(2, errors.size());
  }

  @Test
  public void elementShowAndVisibleAreIndependentAndGates() {
    CompiledPreviewDocument document = parse("""
        {"elements":[
          {"type":"label","show":true,"visible":false,"text":"'A'"},
          {"type":"label","show":false,"visible":true,"text":"'B'"},
          {"type":"label","show":true,"visible":true,"text":"'C'"}
        ]}
        """);
    List<PreviewElement> elements = document.build(PreviewStateContext.statics(Map.of()));
    assertEquals(1, elements.size());
    assertEquals(1, document.visibility(PreviewStateContext.statics(Map.of())).elements().size());
  }

  @Test
  public void repeatShowUsesTheInstanceVariableAndVariantValues() {
    CompiledPreviewDocument document = parse("""
        {"show":"vars.enabled","match":{"vars":{"enabled":false,"limit":0}},
         "variants":[{"blocks":["FURNACE"],"vars":{"enabled":true,"limit":2}}],
         "elements":[{"type":"label","repeat":{"count":4,"var":"row"},
           "show":"{{ row < vars.limit }}","x":"row","text":"str(row)"}]}
        """);
    PreviewStateContext context = PreviewStateContext.statics(document.varsForBlock(Material.FURNACE));
    List<PreviewElement> elements = document.build(context);
    assertEquals(2, elements.size());
    assertEquals(0, elements.getFirst().x());
    assertEquals(1, elements.getLast().x());
    assertTrue(document.hasDynamicVisibility());
    assertTrue(document.build(PreviewStateContext.statics(document.varsForBlock(Material.CHEST))).isEmpty());
  }

  @Test
  public void unknownVariablesAreRejectedAtEveryShowPath() {
    List<String> documents = List.of(
        "{\"show\":\"vars.missing\"}",
        "{\"card\":{\"show\":\"inventory.missing\"}}",
        "{\"elements\":[{\"type\":\"label\",\"text\":\"'A'\",\"show\":\"oops\"}]}");
    List<String> paths = List.of("show", "card.show", "elements[0].show");
    for (int index = 0; index < documents.size(); index++) {
      String json = documents.get(index);
      PreviewDocumentException failure = assertThrows(PreviewDocumentException.class, () -> parse(json));
      assertTrue(failure.getMessage(), failure.getMessage().contains(paths.get(index)));
      assertTrue(failure.getMessage(), failure.getMessage().contains("unknown variable"));
    }
  }

  @Test
  public void showRejectsInvalidShapesAndNonBooleanExpressions() {
    for (String value : List.of("42", "[]", "{}", "\"1 + 2\"", "\"'yes'\"", "\"true &&\"")) {
      assertThrows(PreviewDocumentException.class, () -> parse("{\"show\":" + value + "}"));
    }
  }

  @Test
  public void failedShowHidesAndReportsOnEachBuild() {
    CompiledPreviewDocument document = parse("""
        {"show":"custom.allowed","card":{"title":"'Title'"},
         "elements":[{"type":"label","text":"'Body'"}]}
        """);
    for (int build = 0; build < 2; build++) {
      List<String> errors = new ArrayList<>();
      assertTrue(document.build(PreviewStateContext.statics(Map.of()), errors::add).isEmpty());
      assertEquals(1, errors.size());
      assertTrue(errors.getFirst().contains("show"));
    }
  }

  @Test
  public void liveShowCanHideAndReturnWithinTheSameContext() {
    CompiledPreviewDocument document = parse("""
        {"show":"burnTime > 0","card":{"title":"'Title'"},
         "elements":[{"type":"label","text":"'Body'"}]}
        """);
    PreviewFakes.FurnaceFake furnace = PreviewFakes.furnace().burnTime(0).gameTime(0);
    PreviewStateContext context = PreviewStateContext.forBlock(furnace.build(), null, Map.of());
    assertTrue(document.build(context).isEmpty());
    furnace.burnTime(20).gameTime(1);
    assertEquals(5, document.build(context).size());
    furnace.burnTime(0).gameTime(2);
    assertTrue(document.build(context).isEmpty());
    furnace.burnTime(10).gameTime(3);
    assertEquals(5, document.build(context).size());
  }

  private static CompiledPreviewDocument parse(String json) {
    return PreviewDocumentParser.parse("show.json", json);
  }
}
