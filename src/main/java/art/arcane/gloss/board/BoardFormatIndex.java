package art.arcane.gloss.board;

import com.github.retrooper.packetevents.protocol.score.ScoreFormat;

import java.util.Map;

/**
 * One viewer's authored score formats, keyed by the entry name the sidebar scores each line
 * against. Built by the board render and read by the packet thread, so it is immutable and
 * swapped whole.
 */
public record BoardFormatIndex(String objectiveName, Map<String, ScoreFormat> formats) {
    public BoardFormatIndex {
        formats = Map.copyOf(formats);
    }

    /** Null when there is nothing to decorate, so the packet thread can skip on one reference check. */
    public static BoardFormatIndex of(String objectiveName, Map<String, ScoreFormat> formats) {
        if (objectiveName == null || formats == null || formats.isEmpty()) {
            return null;
        }
        return new BoardFormatIndex(objectiveName, formats);
    }

    public ScoreFormat format(String objective, String entryName) {
        return objectiveName.equals(objective) ? formats.get(entryName) : null;
    }
}
