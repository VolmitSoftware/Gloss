package art.arcane.gloss.board;

import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.kyori.adventure.text.format.Style;

import java.io.IOException;
import java.util.Locale;

/** How the score column renders for one sidebar line. */
@JsonAdapter(BoardLineFormat.Adapter.class)
public enum BoardLineFormat {
    BLANK,
    FIXED,
    STYLED,
    NUMBER;

    ScoreFormat scoreFormat(String value) {
        String rendered = value == null ? "" : value;
        return switch (this) {
            case BLANK -> ScoreFormat.blankScore();
            case NUMBER -> ScoreFormat.styledScore(Style.empty());
            case STYLED -> ScoreFormat.styledScore(TextUtils.parse(rendered).style());
            case FIXED -> ScoreFormat.fixedScore(TextUtils.parse(rendered));
        };
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BoardLineFormat of(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "blank" -> BLANK;
            case "fixed" -> FIXED;
            case "styled" -> STYLED;
            case "number" -> NUMBER;
            default -> throw new IllegalArgumentException(
                "board line format must be one of blank, fixed, styled, number: " + name);
        };
    }

    public static final class Adapter extends TypeAdapter<BoardLineFormat> {
        @Override
        public BoardLineFormat read(JsonReader reader) throws IOException {
            return of(reader.nextString());
        }

        @Override
        public void write(JsonWriter writer, BoardLineFormat format) throws IOException {
            writer.value(format == null ? null : format.wireName());
        }
    }
}
