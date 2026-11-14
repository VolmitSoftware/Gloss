package art.arcane.gloss.bubble;

import art.arcane.gloss.text.ClientTextLines;
import art.arcane.gloss.text.TextCodeVectorFile;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replays the {@code bubbles} section of {@code text_code_vectors.json}: the prefix, the format and
 * the message go through {@link ChatBubblesService#renderTextBlock} and wrap as one block, so the
 * prefix counts toward the wrap width. The editor's {@code text_code_vectors_test.dart} replays the
 * same section against its bubble preview.
 */
class BubbleTextVectorsTest {
    private final TextPipeline pipeline = new TextPipeline(null);

    @TestFactory
    List<DynamicTest> everyBubbleVectorRendersLikeTheClient() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : TextCodeVectorFile.section("bubbles")) {
            JsonObject vector = element.getAsJsonObject();
            String id = vector.get("id").getAsString();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                List<String> lines = ChatBubblesService.renderTextBlock(vector.get("prefix").getAsString(),
                    vector.get("format").getAsString(), vector.get("message").getAsString(),
                    vector.get("wrap").getAsInt(), pipeline::renderStatic);
                assertEquals(TextCodeVectorFile.lines(vector.getAsJsonArray("display")),
                    ClientTextLines.lines(TextUtils.parseLegacy(String.join("\n", lines))), id);
            }));
        }
        return tests;
    }
}
