package art.arcane.gloss.importer;

import java.util.List;

/** AnimatedScoreboard scoreboards and their frame lists as Gloss documents. */
public final class AnimatedScoreboardConverter {
    private final LegacyBoardConverter boards = new LegacyBoardConverter();

    public List<DocumentImportEntry> convert(LegacyBoardDraft draft) {
        return boards.convert(draft);
    }
}
