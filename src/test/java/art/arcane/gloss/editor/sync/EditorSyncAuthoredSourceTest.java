package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EditorSyncAuthoredSourceTest {
  @Test
  void aChangedNumberKeepsItsSpelling() {
    assertEquals("{\"v\": 45.0}", setV("{\"v\": 30.0}", "45"));
    assertEquals("{\"v\": 2.50}", setV("{\"v\": 1.50}", "2.5"));
    assertEquals("{\"v\": 2.00}", setV("{\"v\": 1.50}", "2"));
    assertEquals("{\"v\": 2.125}", setV("{\"v\": 1.50}", "2.125"));
    assertEquals("{\"v\": 0.4}", setV("{\"v\": 0.35}", "0.4"));
    assertEquals("{\"v\": 1.0}", setV("{\"v\": 0.35}", "1"));
    assertEquals("{\"v\": 5.0}", setV("{\"v\": -0.0}", "5"));
    assertEquals("{\"v\": 2e3}", setV("{\"v\": 1e3}", "2000"));
    assertEquals("{\"v\": 1.5E+3}", setV("{\"v\": 1E+3}", "1500"));
    assertEquals("{\"v\": 2.5e-3}", setV("{\"v\": 1e-3}", "0.0025"));
    assertEquals("{\"v\": 7.5}", setV("{\"v\": 7}", "7.5"));
    assertEquals("{\"v\": 8}", setV("{\"v\": 7}", "8.0"));
  }

  @Test
  void aNumberEqualToTheOldOneKeepsTheOldText() {
    String source = "{\"a\": 30.0, \"b\": -0.0, \"c\": 1e3, \"d\": 1.50, \"e\": 4}";
    assertEquals(source, EditorSyncAuthoredSource.edit(source,
        JsonParser.parseString("{\"a\":30,\"b\":0,\"c\":1000,\"d\":1.5,\"e\":4.0}")));
  }

  @Test
  void anAddedKeyFollowsTheFileIndentation() {
    String source = "{\n    \"a\": 1,\n    \"b\": {\n        \"c\": 2\n    }\n}\n";
    String edited = EditorSyncAuthoredSource.edit(source,
        JsonParser.parseString("{\"a\": 1, \"b\": {\"c\": 2, \"d\": [1, 2]}, \"e\": {\"f\": true}}"));
    assertEquals("{\n    \"a\": 1,\n    \"b\": {\n        \"c\": 2,\n        \"d\": [\n            1,\n"
        + "            2\n        ]\n    },\n    \"e\": {\n        \"f\": true\n    }\n}\n", edited);
  }

  @Test
  void aRemovedKeyTakesItsCommaAndCommentsStay() {
    String source = """
        {
          // first
          "a": 1,
          /* second */ "b": 2,

          "c": 3
        }
        """;
    assertEquals("""
        {
          // first
          "a": 1,

          "c": 3
        }
        """, EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"a\": 1, \"c\": 3}")));
    assertEquals("""
        {
          // first
          "a": 1,
          /* second */ "b": 2
        }
        """, EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"a\": 1, \"b\": 2}")));
    assertEquals("""
        {
          /* second */ "b": 2,

          "c": 3
        }
        """, EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"b\": 2, \"c\": 3}")));
  }

  @Test
  void aTrailingCommentLeavesWithItsKey() {
    String source = "{\n  \"a\": 1, // about a\n  \"b\": 2, // about b\n  \"c\": 3 // about c\n}";
    assertEquals("{\n  \"a\": 1, // about a\n  \"c\": 3 // about c\n}",
        EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"a\": 1, \"c\": 3}")));
    assertEquals("{\n  \"a\": 1, // about a\n  \"b\": 2 // about b\n}",
        EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"a\": 1, \"b\": 2}")));
    assertEquals("{\n  \"a\": 1, // about a\n  \"b\": 2, // about b\n  \"c\": 3, // about c\n  \"d\": 4\n}",
        EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"a\": 1, \"b\": 2, \"c\": 3, \"d\": 4}")));
  }

  @Test
  void anArrayEditKeepsTheItemsItDidNotTouch() {
    String source = "{\"lines\": [\"a\", \"b\" /* keep */, \"c\"], \"xs\": [1.0, 2.0]}";
    assertEquals("{\"lines\": [\"a\", \"b\" /* keep */, \"c\", \"d\"], \"xs\": [1.0, 2.0]}",
        EditorSyncAuthoredSource.edit(source, JsonParser.parseString(
            "{\"lines\": [\"a\", \"b\", \"c\", \"d\"], \"xs\": [1, 2]}")));
    assertEquals("{\"lines\": [\"a\", \"c\"], \"xs\": [1.0, 3.0]}",
        EditorSyncAuthoredSource.edit(source, JsonParser.parseString(
            "{\"lines\": [\"a\", \"c\"], \"xs\": [1, 3]}")));
    assertEquals("{\"lines\": [\"z\", \"a\", \"b\" /* keep */, \"c\"], \"xs\": [1.0, 2.0]}",
        EditorSyncAuthoredSource.edit(source, JsonParser.parseString(
            "{\"lines\": [\"z\", \"a\", \"b\", \"c\"], \"xs\": [1, 2]}")));
  }

  @Test
  void anEmptyContainerGetsTheFileLayout() {
    String source = "{\n  \"layers\": [],\n  \"box\": {}\n}";
    assertEquals("{\n  \"layers\": [\n    \"x\"\n  ],\n  \"box\": {\n    \"on\": true\n  }\n}",
        EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"layers\": [\"x\"], \"box\": {\"on\": true}}")));
  }

  @Test
  void textItCannotReadIsLeftToTheCaller() {
    assertNull(EditorSyncAuthoredSource.edit("{\"a\": 1,}", JsonParser.parseString("{\"a\": 2}")));
    assertNull(EditorSyncAuthoredSource.edit("{\"a\": 1 /* open", JsonParser.parseString("{\"a\": 2}")));
    assertNull(EditorSyncAuthoredSource.edit("\"text\"", JsonParser.parseString("{\"a\": 2}")));
  }

  @Test
  void aBoardEditWritesOnlyWhatTheModelChanged() {
    String source = "{\n  \"id\": \"lobby\", // name\n  \"x\": 30.0,\n  \"unknown\": 1e3\n}\n";
    JsonElement before = JsonParser.parseString("{\"id\": \"lobby\", \"x\": 30.0, \"show\": null}");
    JsonElement after = JsonParser.parseString("{\"id\": \"lobby\", \"x\": 45.0, \"show\": null}");
    assertEquals("{\n  \"id\": \"lobby\", // name\n  \"x\": 45.0,\n  \"unknown\": 1e3\n}\n",
        EditorSyncAuthoredSource.edit(source, before, after));
  }

  /**
   * Generated documents (random layout, comments, number spellings) round-trip through the browser's encoding byte
   * for byte, and a one-number edit changes that number's text and nothing else.
   */
  @Test
  void generatedDocumentsKeepEveryUntouchedByte() {
    for (int seed = 0; seed < 400; seed++) {
      Random random = new Random(seed);
      Generated generated = Generated.document(random);
      String source = generated.text.toString();
      JsonElement tree = JsonParser.parseString(source);
      String browser = EditorPublishFormattingTest.browserText(tree);
      assertEquals(source, EditorSyncAuthoredSource.edit(source, JsonParser.parseString(browser)),
          "seed " + seed + " round trip");
      if (generated.numbers.isEmpty()) {
        continue;
      }
      Generated.Spot spot = generated.numbers.get(random.nextInt(generated.numbers.size()));
      BigDecimal old = new BigDecimal(source.substring(spot.start(), spot.end()));
      BigDecimal value = old.add(BigDecimal.valueOf(1 + random.nextInt(500), random.nextInt(3)));
      JsonElement edited = tree.deepCopy();
      set(edited, spot.path(), new JsonPrimitive(value));
      String result = EditorSyncAuthoredSource.edit(source,
          JsonParser.parseString(EditorPublishFormattingTest.browserText(edited)));
      assertNotNull(result, "seed " + seed + " edit");
      String tail = source.substring(spot.end());
      assertEquals(source.substring(0, spot.start()), result.substring(0, spot.start()), "seed " + seed + " head");
      assertEquals(tail, result.substring(result.length() - tail.length()), "seed " + seed + " tail");
      String written = result.substring(spot.start(), result.length() - tail.length());
      assertEquals(0, new BigDecimal(written).compareTo(value), "seed " + seed + " value " + written);
    }
  }

  /** Random structural edits (keys added and removed, items inserted and dropped) still read back as the edit. */
  @Test
  void generatedStructuralEditsReadBackAsTheEdit() {
    for (int seed = 0; seed < 400; seed++) {
      Random random = new Random(seed);
      String source = Generated.document(random).text.toString();
      JsonElement edited = JsonParser.parseString(source);
      for (int change = 0; change < 1 + random.nextInt(3); change++) {
        mutate(edited, random);
      }
      String result = EditorSyncAuthoredSource.edit(source,
          JsonParser.parseString(EditorPublishFormattingTest.browserText(edited)));
      assertNotNull(result, "seed " + seed + "\n" + source + "\n" + edited);
      assertEquals(EditorSyncJson.canonical(edited), EditorSyncJson.canonical(JsonParser.parseString(result)),
          "seed " + seed);
    }
  }

  private static String setV(String source, String value) {
    return EditorSyncAuthoredSource.edit(source, JsonParser.parseString("{\"v\": " + value + "}"));
  }

  private static void set(JsonElement root, List<Object> path, JsonElement value) {
    JsonElement parent = root;
    for (int index = 0; index < path.size() - 1; index++) {
      Object step = path.get(index);
      parent = step instanceof String name ? parent.getAsJsonObject().get(name)
          : parent.getAsJsonArray().get((Integer) step);
    }
    Object last = path.getLast();
    if (last instanceof String name) {
      parent.getAsJsonObject().add(name, value);
    } else {
      parent.getAsJsonArray().set((Integer) last, value);
    }
  }

  private static void mutate(JsonElement root, Random random) {
    List<JsonElement> containers = new ArrayList<>();
    collect(root, containers);
    JsonElement target = containers.get(random.nextInt(containers.size()));
    if (target.isJsonObject()) {
      JsonObject object = target.getAsJsonObject();
      List<String> keys = List.copyOf(object.keySet());
      if (!keys.isEmpty() && random.nextBoolean()) {
        object.remove(keys.get(random.nextInt(keys.size())));
      } else {
        object.add("added" + random.nextInt(1000), Generated.freshValue(random));
      }
    } else {
      JsonArray array = target.getAsJsonArray();
      if (!array.isEmpty() && random.nextBoolean()) {
        array.remove(random.nextInt(array.size()));
      } else {
        JsonArray copy = new JsonArray();
        int at = random.nextInt(array.size() + 1);
        for (int index = 0; index <= array.size(); index++) {
          if (index == at) {
            copy.add(Generated.freshValue(random));
          }
          if (index < array.size()) {
            copy.add(array.get(index));
          }
        }
        while (!array.isEmpty()) {
          array.remove(0);
        }
        array.addAll(copy);
      }
    }
  }

  private static void collect(JsonElement value, List<JsonElement> containers) {
    if (value.isJsonObject()) {
      containers.add(value);
      value.getAsJsonObject().entrySet().forEach(entry -> collect(entry.getValue(), containers));
    } else if (value.isJsonArray()) {
      containers.add(value);
      value.getAsJsonArray().forEach(item -> collect(item, containers));
    }
  }

  /** A random document written straight to text, noting where each number sits. */
  private static final class Generated {
    record Spot(List<Object> path, int start, int end) {
    }

    final StringBuilder text = new StringBuilder();
    final List<Spot> numbers = new ArrayList<>();
    private final Random random;
    private final String unit;
    private int keys;

    private Generated(Random random) {
      this.random = random;
      this.unit = List.of("  ", "    ", "\t").get(random.nextInt(3));
    }

    static Generated document(Random random) {
      Generated generated = new Generated(random);
      generated.object(new ArrayList<>(), "", 0, random.nextInt(4) > 0);
      if (random.nextBoolean()) {
        generated.text.append('\n');
      }
      return generated;
    }

    static JsonElement freshValue(Random random) {
      return switch (random.nextInt(4)) {
        case 0 -> new JsonPrimitive(random.nextInt(100));
        case 1 -> new JsonPrimitive("new" + random.nextInt(10));
        case 2 -> JsonParser.parseString("{\"k\": [1.5, true]}");
        default -> new JsonArray();
      };
    }

    private void object(List<Object> path, String indent, int depth, boolean lines) {
      text.append('{');
      int count = random.nextInt(depth == 0 ? 6 : 4) + (depth == 0 ? 1 : 0);
      String inner = indent + unit;
      for (int index = 0; index < count; index++) {
        separator(index, lines, inner);
        String name = "k" + keys++;
        text.append('"').append(name).append('"').append(List.of(": ", ":", " : ", ":   ").get(random.nextInt(4)));
        List<Object> child = new ArrayList<>(path);
        child.add(name);
        value(child, inner, depth + 1, lines);
      }
      close(count, lines, indent, '}');
    }

    private void array(List<Object> path, String indent, int depth, boolean lines) {
      text.append('[');
      int count = random.nextInt(4);
      String inner = indent + unit;
      for (int index = 0; index < count; index++) {
        separator(index, lines, inner);
        List<Object> child = new ArrayList<>(path);
        child.add(index);
        value(child, inner, depth + 1, lines);
      }
      close(count, lines, indent, ']');
    }

    private void separator(int index, boolean lines, String inner) {
      if (index > 0) {
        text.append(List.of(",", " ,", ",  ").get(random.nextInt(3)));
      }
      if (lines) {
        if (random.nextInt(5) == 0) {
          text.append(" // note ").append(random.nextInt(100));
        }
        text.append('\n');
        if (random.nextInt(6) == 0) {
          text.append('\n');
        }
        text.append(inner);
      } else if (index > 0 || random.nextBoolean()) {
        text.append(' ');
      }
      if (random.nextInt(8) == 0) {
        text.append("/* c */ ");
      }
    }

    private void close(int count, boolean lines, String indent, char close) {
      if (lines && count > 0) {
        text.append('\n').append(indent);
      }
      text.append(close);
    }

    private void value(List<Object> path, String indent, int depth, boolean lines) {
      int kind = random.nextInt(depth > 3 ? 4 : 6);
      switch (kind) {
        case 0, 1 -> {
          int start = text.length();
          text.append(number());
          numbers.add(new Spot(path, start, text.length()));
        }
        case 2 -> text.append(List.of("\"text\"", "\"caf\\u00e9\"", "\"a \\\"b\\\"\"", "true", "null")
            .get(random.nextInt(5)));
        case 3 -> text.append(random.nextBoolean() ? "[]" : "{}");
        case 4 -> object(path, indent, depth, lines && random.nextInt(3) > 0);
        default -> array(path, indent, depth, lines && random.nextInt(3) > 0);
      }
    }

    private String number() {
      int whole = random.nextInt(2000) - 1000;
      return switch (random.nextInt(8)) {
        case 0 -> Integer.toString(whole);
        case 1 -> whole + ".0";
        case 2 -> whole + "." + random.nextInt(10) + "0";
        case 3 -> whole + "." + (1 + random.nextInt(99));
        case 4 -> "-0.0";
        case 5 -> (1 + random.nextInt(9)) + "e" + random.nextInt(4);
        case 6 -> (1 + random.nextInt(9)) + "." + random.nextInt(10) + "E-" + (1 + random.nextInt(3));
        default -> "0.0";
      };
    }
  }
}
