package art.arcane.gloss.importer;

import java.util.List;

/** FeatherBoard boards and their animation frames as Gloss documents. */
public final class FeatherBoardConverter {
    private final LegacyBoardConverter boards = new LegacyBoardConverter();

    public List<DocumentImportEntry> convert(LegacyBoardDraft draft) {
        return boards.convert(draft);
    }
}
