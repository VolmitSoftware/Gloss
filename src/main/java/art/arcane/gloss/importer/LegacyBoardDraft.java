package art.arcane.gloss.importer;

import java.util.List;
import java.util.Objects;

/**
 * One legacy scoreboard read off disk, before it becomes a Gloss board.
 *
 * <p>A title or a line authored as several frames stays several frames here; the converter decides
 * whether that becomes an animation document or a single static line.
 */
record LegacyBoardDraft(String id, List<String> titleFrames, int titleIntervalTicks,
                        List<Line> lines, List<String> warnings) {
    LegacyBoardDraft {
        id = Objects.requireNonNull(id, "id");
        titleFrames = List.copyOf(titleFrames);
        lines = List.copyOf(lines);
        warnings = List.copyOf(warnings);
    }

    record Line(List<String> frames, int intervalTicks) {
        Line {
            frames = List.copyOf(frames);
        }

        boolean animated() {
            return frames.size() > 1;
        }
    }
}
