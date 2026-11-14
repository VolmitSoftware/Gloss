package art.arcane.gloss.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Reads {@code src/test/resources/text_code_vectors.json}, shared with the editor's copy. */
public final class TextCodeVectorFile {
    private static final String RESOURCE_NAME = "text_code_vectors.json";

    private TextCodeVectorFile() {
    }

    /** One section of the file: {@code vectors}, {@code nametags} or {@code bubbles}. */
    public static JsonArray section(String name) throws IOException {
        try (InputStream in = TextCodeVectorFile.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            assertNotNull(in, "missing test resource: " + RESOURCE_NAME);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonArray section = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray(name);
                assertNotNull(section, "missing section: " + name);
                return section;
            }
        }
    }

    /** Expected lines of runs, as the file writes them. */
    public static List<List<ClientTextLines.Run>> lines(JsonArray lines) {
        List<List<ClientTextLines.Run>> out = new ArrayList<>();
        for (JsonElement line : lines) {
            List<ClientTextLines.Run> runs = new ArrayList<>();
            for (JsonElement element : line.getAsJsonArray()) {
                JsonObject run = element.getAsJsonObject();
                runs.add(new ClientTextLines.Run(
                    run.get("text").getAsString(),
                    Integer.parseInt(run.get("color").getAsString().substring(1), 16),
                    flag(run, "bold"), flag(run, "italic"), flag(run, "underlined"),
                    flag(run, "strikethrough"), flag(run, "obfuscated")));
            }
            out.add(runs);
        }
        return out;
    }

    private static boolean flag(JsonObject run, String name) {
        return run.has(name) && run.get(name).getAsBoolean();
    }
}
