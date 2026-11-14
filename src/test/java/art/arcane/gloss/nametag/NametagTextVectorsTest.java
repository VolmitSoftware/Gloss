package art.arcane.gloss.nametag;

import art.arcane.gloss.text.ClientTextLines;
import art.arcane.gloss.text.TextCodeVectorFile;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Replays the {@code nametags} section of {@code text_code_vectors.json}. The editor's
 * {@code text_code_vectors_test.dart} replays the same section against its nametag previews.
 *
 * <p>Above the head, {@code PacketTeamAllocator} sends the parsed prefix and suffix with the team
 * colour, and the client draws {@code PlayerTeam.getFormattedName}: an empty component in the team
 * colour holding the prefix, the name and the suffix, so an uncoloured prefix takes the team colour.
 * Nameplates show the legacy display name from {@link NametagService#formatName}, where the prefix
 * stands on its own.
 */
class NametagTextVectorsTest {
    private final TextPipeline pipeline = new TextPipeline(null);

    @TestFactory
    List<DynamicTest> everyNametagVectorRendersLikeTheClient() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement element : TextCodeVectorFile.section("nametags")) {
            JsonObject vector = element.getAsJsonObject();
            String id = vector.get("id").getAsString();
            String name = vector.get("name").getAsString();
            String color = vector.get("color").getAsString();
            String prefix = pipeline.renderStatic(vector.get("prefix").getAsString());
            String suffix = pipeline.renderStatic(vector.get("suffix").getAsString());
            tests.add(DynamicTest.dynamicTest(id + " / above the head", () -> {
                NamedTextColor teamColor = NamedTextColor.NAMES.value(color);
                assertNotNull(teamColor, "team colour " + color);
                Component formatted = Component.empty()
                    .append(TextUtils.parse(prefix))
                    .append(Component.text(name))
                    .append(TextUtils.parse(suffix))
                    .color(teamColor);
                assertEquals(TextCodeVectorFile.lines(vector.getAsJsonArray("team")),
                    ClientTextLines.lines(formatted), id + " above the head");
            }));
            tests.add(DynamicTest.dynamicTest(id + " / nameplate display name", () ->
                assertEquals(TextCodeVectorFile.lines(vector.getAsJsonArray("nameplate")),
                    ClientTextLines.lines(TextUtils.parseLegacy(NametagService.formatName(name, color, prefix, suffix))),
                    id + " as a nameplate name")));
        }
        return tests;
    }
}
