package art.arcane.gloss.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a legacy scoreboard into a Gloss board plus one animation document per animated line.
 *
 * <p>Animated text is the one thing both FeatherBoard and AnimatedScoreboard have that a board line
 * does not: a list of frames with an interval. Gloss already has that as an {@code animations/}
 * document referenced by {@code |animation.&lt;id&gt;|}, so each frame list becomes one animation and
 * the line becomes its token.
 */
public final class LegacyBoardConverter {
    private static final int SCHEMA_VERSION = 2;
    private static final int ANIMATION_SCHEMA_VERSION = 1;
    private static final long INITIAL_REVISION = 1L;
    private static final long TICK_MILLIS = 50L;

    public List<DocumentImportEntry> convert(LegacyBoardDraft draft) {
        List<DocumentImportEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>(draft.warnings());
        String boardId = slug(draft.id());
        JsonArray lines = new JsonArray();
        int index = 0;
        for (LegacyBoardDraft.Line line : draft.lines()) {
            index++;
            if (!line.animated()) {
                lines.add(line.frames().isEmpty() ? "" : line.frames().getFirst());
                continue;
            }
            String animationId = boardId + "-line-" + index;
            entries.add(animation(animationId, line.frames(), line.intervalTicks()));
            lines.add("|animation." + animationId + "|");
        }
        String title;
        if (draft.titleFrames().size() > 1) {
            String animationId = boardId + "-title";
            entries.add(animation(animationId, draft.titleFrames(), draft.titleIntervalTicks()));
            title = "|animation." + animationId + "|";
        } else {
            title = draft.titleFrames().isEmpty() ? "" : draft.titleFrames().getFirst();
        }
        JsonObject presentation = new JsonObject();
        presentation.addProperty("title", title);
        presentation.add("lines", lines);
        JsonObject select = new JsonObject();
        select.addProperty("priority", 0);
        select.addProperty("when", "false");
        JsonObject board = new JsonObject();
        board.addProperty("schemaVersion", SCHEMA_VERSION);
        board.addProperty("revision", INITIAL_REVISION);
        board.addProperty("show", true);
        board.add("select", select);
        board.add("presentation", presentation);
        board.add("variants", new JsonArray());
        warnings.add("the imported board never selects itself; set select.when before using it");
        entries.add(new DocumentImportEntry("boards", boardId, pretty(board),
                LegacyImportDisposition.READY, "", warnings));
        return List.copyOf(entries);
    }

    private DocumentImportEntry animation(String id, List<String> frames, int intervalTicks) {
        JsonArray values = new JsonArray();
        frames.forEach(values::add);
        JsonObject animation = new JsonObject();
        animation.addProperty("schemaVersion", ANIMATION_SCHEMA_VERSION);
        animation.addProperty("revision", INITIAL_REVISION);
        animation.addProperty("show", true);
        animation.addProperty("mode", "ascend");
        animation.addProperty("frameIntervalMs", Math.max(1L, intervalTicks * TICK_MILLIS));
        animation.add("frames", values);
        return new DocumentImportEntry("animations", id, pretty(animation),
                LegacyImportDisposition.READY, "", List.of());
    }

    static String slug(String value) {
        StringBuilder slug = new StringBuilder(value.length());
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            slug.append(Character.isLetterOrDigit(character) || character == '-' || character == '_'
                    ? character : '-');
        }
        String normalized = slug.toString().replaceAll("-{2,}", "-");
        normalized = normalized.startsWith("-") ? normalized.substring(1) : normalized;
        normalized = normalized.endsWith("-")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        return normalized.isBlank() ? "imported" : normalized;
    }

    static String pretty(JsonObject document) {
        return new com.google.gson.GsonBuilder()
                .serializeNulls()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .create()
                .toJson(document) + System.lineSeparator();
    }
}
