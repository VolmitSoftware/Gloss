package art.arcane.gloss.text;

import art.arcane.gloss.bubble.BubbleTextBlock;
import art.arcane.gloss.hologram.HologramService;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.board.BoardTextFormat;
import art.arcane.volmlib.util.format.ColorFormatter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays {@code src/test/resources/text_code_vectors.json}, the cross-repo contract for how
 * colour and format codes combine. The editor's {@code text_code_vectors_test.dart} replays the
 * same file against its previews.
 *
 * <p>Each vector is sent the ways the plugin sends text and read back the way the client draws
 * it ({@link ClientTextLines}). One text display or component (holograms, bubbles, overlays,
 * labels, the MOTD, tab header and footer) must match {@code display}; one entity or component per
 * line (menu text icons, scoreboard rows) must match {@code perLine}. Shapes that only read legacy
 * codes skip the MiniMessage vectors. A vector with {@code name} or {@code lore} is also read as an
 * item's custom name (the whole text) or its lore (one line each), over the tooltip defaults.
 */
class TextCodeVectorsTest {
    private static final int MINIMUM_VECTOR_COUNT = 16;

    private final TextPipeline pipeline = new TextPipeline(null);

    private record Shape(String name, boolean display, boolean miniMessage,
                         Function<String, List<List<ClientTextLines.Run>>> send) {
    }

    private List<Shape> shapes() {
        return List.of(
            new Shape("display, lines rendered then joined (per-viewer holograms)", true, true,
                text -> ClientTextLines.lines(TextUtils.parse(joinRendered(text, pipeline::renderStatic)))),
            new Shape("display, whole text rendered (overlays, nameplates)", true, true,
                text -> ClientTextLines.lines(TextUtils.parse(pipeline.renderStatic(text)))),
            new Shape("display, static lines (shared and temporary holograms, indicators)", true, true,
                text -> ClientTextLines.lines(TextUtils.parseLegacy(
                    HologramService.renderStaticLines(pipeline, List.of(text.split("\n", -1)))))),
            new Shape("display, legacy codes only (animated temporary holograms, tab header and footer)", true, false,
                text -> ClientTextLines.lines(TextUtils.parseLegacy(pipeline.renderStatic(text)))),
            new Shape("display, chat bubble message", true, false,
                text -> ClientTextLines.lines(TextUtils.parseLegacy(String.join("\n",
                    BubbleTextBlock.wrap("", ColorFormatter.translateColors(text), 1000))))),
            new Shape("one entity per line (menu text icons)", false, true,
                text -> {
                    List<Component> lines = new ArrayList<>();
                    for (String line : text.split("\n")) {
                        lines.add(TextUtils.parse(pipeline.renderMenuText(null, line)));
                    }
                    return ClientTextLines.entityLines(lines);
                }),
            new Shape("one row per line (scoreboard)", false, true,
                text -> {
                    List<Component> rows = new ArrayList<>();
                    for (String line : text.split("\n")) {
                        rows.add(GsonComponentSerializer.gson().deserialize(
                            BoardTextFormat.MINI_MESSAGE.json(pipeline.renderStatic(line))));
                    }
                    return ClientTextLines.entityLines(rows);
                }));
    }

    private static String joinRendered(String text, Function<String, String> render) {
        List<String> rendered = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            rendered.add(render.apply(line));
        }
        return String.join("\n", rendered);
    }

    @TestFactory
    List<DynamicTest> itemTextRendersLikeTheTooltip() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : TextCodeVectorFile.section("vectors")) {
            JsonObject vector = element.getAsJsonObject();
            String id = vector.get("id").getAsString();
            String text = vector.get("text").getAsString();
            if (vector.has("name")) {
                tests.add(DynamicTest.dynamicTest(id + " / item name", () -> assertEquals(
                    TextCodeVectorFile.lines(vector.getAsJsonArray("name")),
                    ClientTextLines.customName(itemText(text)), id + " as an item name")));
            }
            if (vector.has("lore")) {
                tests.add(DynamicTest.dynamicTest(id + " / item lore", () -> {
                    List<Component> lore = new ArrayList<>();
                    for (String line : text.split("\n")) {
                        lore.add(itemText(line));
                    }
                    assertEquals(TextCodeVectorFile.lines(vector.getAsJsonArray("lore")),
                        ClientTextLines.loreLines(lore), id + " as item lore");
                }));
            }
        }
        return tests;
    }

    private Component itemText(String text) {
        return TextUtils.parseOverFormats(pipeline.renderMenuText(null, text));
    }

    @TestFactory
    List<DynamicTest> everyVectorRendersLikeTheClient() throws IOException {
        JsonArray vectors = TextCodeVectorFile.section("vectors");
        assertTrue(vectors.size() >= MINIMUM_VECTOR_COUNT,
            "expected at least " + MINIMUM_VECTOR_COUNT + " vectors, found " + vectors.size());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : vectors) {
            JsonObject vector = element.getAsJsonObject();
            String id = vector.get("id").getAsString();
            String text = vector.get("text").getAsString();
            boolean miniMessage = vector.has("syntax") && vector.get("syntax").getAsString().equals("minimessage");
            for (Shape shape : shapes()) {
                if (miniMessage && !shape.miniMessage()) {
                    continue;
                }
                List<List<ClientTextLines.Run>> expected = TextCodeVectorFile.lines(vector.getAsJsonArray(
                    shape.display() ? "display" : "perLine"));
                tests.add(DynamicTest.dynamicTest(id + " / " + shape.name(),
                    () -> assertEquals(expected, shape.send().apply(text), id + " sent as " + shape.name())));
            }
        }
        return tests;
    }
}
