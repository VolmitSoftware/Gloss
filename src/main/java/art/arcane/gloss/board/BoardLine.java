package art.arcane.gloss.board;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * One sidebar row: the label on the left and, optionally, the value the score column shows on the
 * right. A row authored as a plain string carries no value and reads back as a plain string.
 */
@JsonAdapter(BoardLine.Adapter.class)
public record BoardLine(String text, String value, BoardLineFormat format) {
    public BoardLine {
        text = text == null ? "" : text;
        value = value == null || value.isEmpty() ? null : value;
    }

    public static BoardLine of(String text) {
        return new BoardLine(text, null, null);
    }

    public boolean isPlainText() {
        return value == null && format == null;
    }

    public static final class Adapter extends TypeAdapter<BoardLine> {
        @Override
        public BoardLine read(JsonReader reader) throws IOException {
            JsonToken token = reader.peek();
            if (token == JsonToken.NULL) {
                reader.nextNull();
                return BoardLine.of("");
            }
            if (token == JsonToken.STRING) {
                return BoardLine.of(reader.nextString());
            }
            if (token != JsonToken.BEGIN_OBJECT) {
                throw new IllegalArgumentException("board line must be a string or an object at " + reader.getPath());
            }
            String text = "";
            String value = null;
            BoardLineFormat format = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "text" -> text = reader.nextString();
                    case "value" -> value = reader.nextString();
                    case "format" -> format = BoardLineFormat.of(reader.nextString());
                    default -> throw new IllegalArgumentException(
                        "board line has an unknown field \"" + name + "\" at " + reader.getPath());
                }
            }
            reader.endObject();
            return new BoardLine(text, value, format);
        }

        @Override
        public void write(JsonWriter writer, BoardLine line) throws IOException {
            if (line == null) {
                writer.nullValue();
                return;
            }
            if (line.isPlainText()) {
                writer.value(line.text());
                return;
            }
            writer.beginObject();
            writer.name("text").value(line.text());
            if (line.value() != null) {
                writer.name("value").value(line.value());
            }
            if (line.format() != null) {
                writer.name("format").value(line.format().wireName());
            }
            writer.endObject();
        }
    }
}
