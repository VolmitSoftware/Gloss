package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.text.TextPipeline;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

public final class GlossBoardMeta {
    public static final String UNRESTRICTED_PERMISSION = "default";
    public static final String PERMISSION_NODE_PREFIX = "gloss.board.";

    private final String id;
    private final CopyOnWriteArrayList<String> content;
    private final AtomicLong contentGeneration;
    private volatile String title;
    private volatile boolean primary;
    private volatile boolean hideNumbers;
    private volatile String permission;
    private volatile List<String> groups;
    private volatile long revision;
    private volatile RenderPlan renderPlan;

    public GlossBoardMeta(String id) {
        this.id = id;
        this.content = new CopyOnWriteArrayList<>();
        this.contentGeneration = new AtomicLong();
        this.title = id;
        this.primary = false;
        this.hideNumbers = false;
        this.permission = UNRESTRICTED_PERMISSION;
        this.groups = List.of();
        this.revision = 0L;
    }

    public static GlossBoardMeta fromDoc(String id, BoardDoc doc) {
        GlossBoardMeta meta = new GlossBoardMeta(id);
        meta.setTitle(doc.title().isEmpty() ? id : doc.title());
        for (String line : doc.lines()) {
            meta.addLine(line);
        }
        meta.setPrimary(doc.primary());
        meta.setHideNumbers(doc.hideNumbers());
        meta.setPermission(doc.permission());
        meta.setGroups(doc.groups());
        meta.revision = doc.revision();
        return meta;
    }

    public BoardDoc toDoc(long revision) {
        return new BoardDoc(BoardDoc.CURRENT_SCHEMA_VERSION, revision, title, lines(), primary, hideNumbers,
            permission, groups);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? id : title;
        contentGeneration.incrementAndGet();
    }

    public List<String> lines() {
        return List.copyOf(content);
    }

    public void addLine(String line) {
        content.add(line == null ? "" : line);
        contentGeneration.incrementAndGet();
    }

    public void setLine(int index, String line) {
        content.set(index, line == null ? "" : line);
        contentGeneration.incrementAndGet();
    }

    public void removeLine(int index) {
        content.remove(index);
        contentGeneration.incrementAndGet();
    }

    public boolean primary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public boolean hideNumbers() {
        return hideNumbers;
    }

    public void setHideNumbers(boolean hideNumbers) {
        this.hideNumbers = hideNumbers;
    }

    public String permission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = BoardDoc.normalizePermission(permission);
    }

    public boolean permissionGated() {
        return !UNRESTRICTED_PERMISSION.equals(permission);
    }

    public String permissionNode() {
        return PERMISSION_NODE_PREFIX + permission;
    }

    public List<String> groups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public long revision() {
        return revision;
    }

    long nextRevision() {
        long next = revision >= DocumentEnvelope.MAX_SAFE_REVISION
            ? DocumentEnvelope.MAX_SAFE_REVISION
            : revision + 1L;
        revision = next;
        return next;
    }

    /**
     * Returns the cached render plan for the current content and emoji generation,
     * rebuilding it when either changed. Lines without placeholders and functions are
     * viewer-independent, so their rendered value is computed once and shared.
     */
    RenderPlan renderPlan(long emojiGeneration, int maxTitleLength, int maxLines, UnaryOperator<String> staticRender) {
        RenderPlan current = renderPlan;
        long generation = contentGeneration.get();
        if (current != null && current.matches(generation, emojiGeneration)) {
            return current;
        }
        RenderPlan built = RenderPlan.build(generation, emojiGeneration, title, content,
            maxTitleLength, maxLines, staticRender);
        renderPlan = built;
        return built;
    }

    /**
     * Immutable per-board render memo. {@code staticTitle}/{@code staticLines} entries are
     * pre-rendered (already truncated for the title); a {@code null} entry means the value
     * is viewer-dependent and must be rendered per player from the corresponding raw value.
     */
    static final class RenderPlan {
        private static final int DYNAMIC_FLAGS = TextPipeline.HAS_PLACEHOLDER | TextPipeline.HAS_FUNCTION;

        private final long contentGeneration;
        private final long emojiGeneration;
        private final String rawTitle;
        private final String staticTitle;
        private final String[] rawLines;
        private final String[] staticLines;

        private RenderPlan(long contentGeneration, long emojiGeneration, String rawTitle, String staticTitle,
                           String[] rawLines, String[] staticLines) {
            this.contentGeneration = contentGeneration;
            this.emojiGeneration = emojiGeneration;
            this.rawTitle = rawTitle;
            this.staticTitle = staticTitle;
            this.rawLines = rawLines;
            this.staticLines = staticLines;
        }

        static RenderPlan build(long contentGeneration, long emojiGeneration, String title, List<String> content,
                                int maxTitleLength, int maxLines, UnaryOperator<String> staticRender) {
            String rawTitle = title == null ? "" : title;
            String staticTitle = null;
            if ((TextPipeline.classify(rawTitle) & DYNAMIC_FLAGS) == 0) {
                staticTitle = truncate(renderValue(rawTitle, staticRender), maxTitleLength);
            }

            Object[] snapshot = content.toArray();
            int count = Math.min(snapshot.length, maxLines);
            String[] rawLines = new String[count];
            String[] staticLines = new String[count];
            for (int i = 0; i < count; i++) {
                String raw = (String) snapshot[i];
                rawLines[i] = raw;
                staticLines[i] = (TextPipeline.classify(raw) & DYNAMIC_FLAGS) == 0
                    ? renderValue(raw, staticRender)
                    : null;
            }
            return new RenderPlan(contentGeneration, emojiGeneration, rawTitle, staticTitle, rawLines, staticLines);
        }

        private static String renderValue(String raw, UnaryOperator<String> staticRender) {
            if (TextPipeline.classify(raw) == 0) {
                return raw;
            }
            String rendered = staticRender.apply(raw);
            return rendered == null ? "" : rendered;
        }

        private static String truncate(String value, int maxLength) {
            return value.length() > maxLength ? value.substring(0, maxLength) : value;
        }

        boolean matches(long contentGeneration, long emojiGeneration) {
            return this.contentGeneration == contentGeneration && this.emojiGeneration == emojiGeneration;
        }

        String rawTitle() {
            return rawTitle;
        }

        String staticTitle() {
            return staticTitle;
        }

        int lineCount() {
            return rawLines.length;
        }

        String rawLine(int index) {
            return rawLines[index];
        }

        String staticLine(int index) {
            return staticLines[index];
        }
    }
}
