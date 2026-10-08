package art.arcane.gloss.board;

import com.github.retrooper.packetevents.protocol.score.ScoreFormat;

import java.util.Map;

/**
 * One viewer's authored score formats, keyed by the entry name the sidebar scores each line
 * against. Built by the board render and read by the packet thread, so it is immutable and
 * swapped whole.
 */
public record BoardFormatIndex(String objectiveName, Map<String, ScoreFormat> formats, boolean hideNumbers) {
    public BoardFormatIndex {
        formats = Map.copyOf(formats);
    }

    /** Null when no sidebar objective is owned; empty formats retain the default number policy. */
    public static BoardFormatIndex of(String objectiveName, Map<String, ScoreFormat> formats, boolean hideNumbers) {
        if (objectiveName == null || formats == null) {
            return null;
        }
        return new BoardFormatIndex(objectiveName, formats, hideNumbers);
    }

    public ScoreFormat format(String objective, String entryName) {
        return objectiveName.equals(objective)
            ? formats.getOrDefault(entryName, hideNumbers ? ScoreFormat.blankScore() : null) : null;
    }
}
