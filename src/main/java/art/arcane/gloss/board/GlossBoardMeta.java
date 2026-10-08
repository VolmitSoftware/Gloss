package art.arcane.gloss.board;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.text.TextPipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

public final class GlossBoardMeta {
    private static final int TEXT_REFRESH = 1;
    private static final int STRUCTURAL_REFRESH = 2;
    private final String id;
    private final CopyOnWriteArrayList<BoardLine> content;
    private final AtomicLong contentGeneration;
    private final Map<String, CachedRenderPlan> renderPlans;
    private volatile String title;
    private volatile boolean hideNumbers;
    private volatile BoardLayout layout = BoardLayout.DEFAULTS;
    private volatile BoardObjectives objectives = BoardObjectives.NONE;
    private volatile ShowCondition show = ShowCondition.ALWAYS;
    private volatile BoardDoc.Selection selection;
    private volatile List<BoardDoc.Variant> variants;
    private volatile CompiledCondition selectionCondition;
    private volatile List<CompiledVariant> compiledVariants;
    private volatile CachedBase base;
    private volatile CachedFastRefresh fastRefreshText;
    private volatile long revision;

    public GlossBoardMeta(String id) {
        this.id = id;
        this.content = new CopyOnWriteArrayList<>();
        this.contentGeneration = new AtomicLong();
        this.renderPlans = new ConcurrentHashMap<>();
        this.title = id;
        this.hideNumbers = false;
        this.selection = BoardDoc.Selection.NEVER;
        this.variants = List.of();
        this.selectionCondition = compileSelection(this.selection);
        this.compiledVariants = List.of();
        this.revision = 0L;
    }

    public static GlossBoardMeta fromDoc(String id, BoardDoc doc) {
        GlossBoardMeta meta = new GlossBoardMeta(id);
        BoardDoc.Presentation presentation = doc.presentation();
        meta.setTitle(presentation.title());
        for (BoardLine line : presentation.lines()) {
            meta.addLine(line);
        }
        meta.setHideNumbers(presentation.hideNumbers());
        meta.layout = presentation.layout();
        meta.objectives = doc.objectives();
        meta.setShow(doc.show());
        meta.setSelection(doc.select().priority(), doc.select().when());
        meta.setVariants(doc.variants());
        meta.revision = doc.revision();
        return meta;
    }

    public BoardDoc toDoc(long revision) {
        return new BoardDoc(BoardDoc.CURRENT_SCHEMA_VERSION, revision, show, selection, presentation(), variants, objectives);
    }

    public BoardObjectives objectives() {
        return objectives;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? id : title;
        contentChanged();
    }

    /** The label column only; {@link #boardLines()} carries the value column with it. */
    public List<String> lines() {
        List<String> texts = new ArrayList<>(content.size());
        for (BoardLine line : content) {
            texts.add(line.text());
        }
        return List.copyOf(texts);
    }

    public List<BoardLine> boardLines() {
        return List.copyOf(content);
    }

    public void addLine(String line) {
        addLine(BoardLine.of(line));
    }

    public void addLine(BoardLine line) {
        content.add(line == null ? BoardLine.of("") : line);
        contentChanged();
    }

    /** Replaces the label of an existing row, keeping whatever value column it already carries. */
    public void setLine(int index, String line) {
        BoardLine current = content.get(index);
        content.set(index, new BoardLine(line == null ? "" : line, current.value(), current.format(), current.id(), current.show(), current.section()));
        contentChanged();
    }

    public void setLine(int index, BoardLine line) {
        content.set(index, line == null ? BoardLine.of("") : line);
        contentChanged();
    }

    public void removeLine(int index) {
        content.remove(index);
        contentChanged();
    }

    public boolean hideNumbers() {
        return hideNumbers;
    }

    public void setHideNumbers(boolean hideNumbers) {
        this.hideNumbers = hideNumbers;
        contentChanged();
    }

    public BoardDoc.Selection selection() {
        return selection;
    }

    public ShowCondition show() {
        return show;
    }

    public void setShow(ShowCondition show) {
        this.show = show == null ? ShowCondition.ALWAYS : show;
    }

    public void setSelection(int priority, String when) {
        BoardDoc.Selection next = new BoardDoc.Selection(priority, when);
        selection = next;
        selectionCondition = compileSelection(next);
    }

    public List<BoardDoc.Variant> variants() {
        return variants;
    }

    public void setVariants(List<BoardDoc.Variant> variants) {
        this.variants = variants == null ? List.of() : List.copyOf(variants);
        this.compiledVariants = compileVariants(this.variants);
        contentChanged();
    }

    boolean matchesSelection(ExprScope scope, BoundedConditionErrorCallback errors) {
        return show.matches(scope, errors) && selectionCondition.matches(scope, errors);
    }

    ActiveProfile activeProfile(ExprScope scope, BoundedConditionErrorCallback errors) {
        for (CompiledVariant variant : compiledVariants) {
            if (variant.condition().matches(scope, errors)) {
                return new ActiveProfile(variant.variant().id(), variant.variant().presentation(), variant.renderRequiresScope());
            }
        }
        return baseProfile();
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
     * The board's own presentation, cached because the selection sweep asks for it several times
     * per viewer per interval and {@link BoardDoc.Presentation} copies its line list.
     */
    BoardDoc.Presentation presentation() {
        return baseProfile().presentation();
    }

    /**
     * Whether any line of this board, in the base presentation or in a variant, needs the one-tick
     * cadence. It is a pure function of the document, so it is computed once per content revision
     * instead of re-parsing every expression per viewer per selection sweep.
     */
    boolean usesFastRefreshText() {
        return usesFastRefresh(true);
    }

    boolean usesFastRefresh(boolean functionsEnabled) {
        long generation = contentGeneration.get();
        long emojiGeneration = TextPipeline.emojiGeneration();
        CachedFastRefresh current = fastRefreshText;
        if (current != null && current.contentGeneration() == generation
            && current.emojiGeneration() == emojiGeneration) {
            return needsFastRefresh(current.flags(), functionsEnabled);
        }
        int flags = refreshFlags(presentation()) | (objectives.isEmpty() ? 0 : STRUCTURAL_REFRESH);
        for (BoardDoc.Variant variant : variants) {
            if (flags == (TEXT_REFRESH | STRUCTURAL_REFRESH)) {
                break;
            }
            flags |= refreshFlags(variant.presentation());
        }
        fastRefreshText = new CachedFastRefresh(generation, emojiGeneration, flags);
        return needsFastRefresh(flags, functionsEnabled);
    }

    private static boolean needsFastRefresh(int flags, boolean functionsEnabled) {
        return (flags & STRUCTURAL_REFRESH) != 0 || functionsEnabled && (flags & TEXT_REFRESH) != 0;
    }

    private static int refreshFlags(BoardDoc.Presentation presentation) {
        int flags = presentation.layout().needsTickDriver() ? STRUCTURAL_REFRESH : 0;
        flags |= TextPipeline.requiresFastRefresh(presentation.title()) ? TEXT_REFRESH : 0;
        for (BoardLine line : presentation.layout().expand(presentation.lines())) {
            flags |= line.show().isDynamic() ? STRUCTURAL_REFRESH : 0;
            if (TextPipeline.requiresFastRefresh(line.text())
                || line.value() != null && TextPipeline.requiresFastRefresh(line.value())) {
                flags |= TEXT_REFRESH;
            }
            if (flags == (TEXT_REFRESH | STRUCTURAL_REFRESH)) {
                break;
            }
        }
        return flags;
    }

    private ActiveProfile baseProfile() {
        long generation = contentGeneration.get();
        CachedBase current = base;
        if (current != null && current.contentGeneration() == generation) {
            return current.profile();
        }
        BoardDoc.Presentation presentation = new BoardDoc.Presentation(title, boardLines(), hideNumbers, layout);
        ActiveProfile built = new ActiveProfile("base", presentation, renderRequiresScope(presentation));
        base = new CachedBase(generation, built);
        return built;
    }

    public RenderPlan renderPlan(String profileId, BoardDoc.Presentation presentation, long emojiGeneration,
                                 int maxLines, UnaryOperator<String> staticRender) {
        return renderPlan(profileId, presentation, emojiGeneration, maxLines, staticRender, null);
    }

    RenderPlan renderPlan(String profileId, BoardDoc.Presentation presentation, long emojiGeneration,
                          int maxLines, UnaryOperator<String> staticRender, BoardLayout.Page page) {
        String key = profileId + (page == null ? ":base" : ":page:" + page.id()) + ":" + maxLines;
        CachedRenderPlan current = renderPlans.get(key);
        long generation = contentGeneration.get();
        if (current != null && current.presentation() == presentation && current.page() == page
            && current.plan().matches(generation, emojiGeneration)) {
            return current.plan();
        }
        RenderPlan built = RenderPlan.build(generation, emojiGeneration,
            page == null || page.title() == null ? presentation.title() : page.title(),
            presentation.layout().expand(page == null ? presentation.lines() : page.lines()),
            maxLines, staticRender, presentation.layout().refresh());
        renderPlans.put(key, new CachedRenderPlan(presentation, page, built));
        return built;
    }

    private record CachedRenderPlan(BoardDoc.Presentation presentation, BoardLayout.Page page, RenderPlan plan) {
    }

    private void contentChanged() {
        contentGeneration.incrementAndGet();
        renderPlans.clear();
    }

    private CompiledCondition compileSelection(BoardDoc.Selection value) {
        return ConditionCompiler.compile(new ConditionSource(
            "boards/" + id + ".select.when", value.when()));
    }

    private List<CompiledVariant> compileVariants(List<BoardDoc.Variant> values) {
        List<CompiledVariant> compiled = new ArrayList<>(values.size());
        for (BoardDoc.Variant value : values) {
            CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
                "boards/" + id + ".variants." + value.id() + ".when", value.when()));
            compiled.add(new CompiledVariant(value, condition, renderRequiresScope(value.presentation())));
        }
        compiled.sort(Comparator
            .comparingInt((CompiledVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));
        return List.copyOf(compiled);
    }

    private static boolean renderRequiresScope(BoardDoc.Presentation presentation) {
        BoardLayout layout = presentation.layout();
        for (BoardLine row : layout.expand(presentation.lines())) {
            if (row.show().requiresScope()) {
                return true;
            }
        }
        for (BoardLayout.Page page : layout.pages()) {
            if (page.show().requiresScope()) {
                return true;
            }
            for (BoardLine row : layout.expand(page.lines())) {
                if (row.show().requiresScope()) {
                    return true;
                }
            }
        }
        return false;
    }

    record ActiveProfile(String id, BoardDoc.Presentation presentation, boolean renderRequiresScope) {
    }

    /**
     * Both memos carry the generations they were derived from, so a reader that started before an
     * edit can never publish a stale value over a newer one: the stamp simply stops matching.
     * {@code usesFastRefreshText} also depends on the published conditional-emoji table, which the
     * emoji registry republishes independently of any board document.
     */
    private record CachedBase(long contentGeneration, ActiveProfile profile) {
    }

    private record CachedFastRefresh(long contentGeneration, long emojiGeneration, int flags) {
    }

    private record CompiledVariant(BoardDoc.Variant variant, CompiledCondition condition, boolean renderRequiresScope) {
    }

    /**
     * Immutable per-board render memo. {@code staticTitle}/{@code staticLines} entries are
     * pre-rendered; a {@code null} entry means the value
     * is viewer-dependent and must be rendered per player from the corresponding raw value.
     */
    public static final class RenderPlan {
        private static final int DYNAMIC_FLAGS = TextPipeline.HAS_PLACEHOLDER | TextPipeline.HAS_FUNCTION;

        private final long contentGeneration;
        private final long emojiGeneration;
        private final String rawTitle;
        private final String staticTitle;
        private final boolean fastTitle;
        private final String[] rawLines;
        private final String[] staticLines;
        private final boolean[] fastLines;
        private final String[] rawValues;
        private final String[] staticValues;
        private final BoardLineFormat[] formats;
        private final BoardLine[] rows;
        private final String[] rowIds;
        private final BoardLayout.Refresh refresh;

        private RenderPlan(long contentGeneration, long emojiGeneration, String rawTitle, String staticTitle,
                           boolean fastTitle, String[] rawLines, String[] staticLines, boolean[] fastLines,
                           String[] rawValues, String[] staticValues, BoardLineFormat[] formats, BoardLine[] rows,
                           String[] rowIds, BoardLayout.Refresh refresh) {
            this.contentGeneration = contentGeneration;
            this.emojiGeneration = emojiGeneration;
            this.rawTitle = rawTitle;
            this.staticTitle = staticTitle;
            this.fastTitle = fastTitle;
            this.rawLines = rawLines;
            this.staticLines = staticLines;
            this.fastLines = fastLines;
            this.rawValues = rawValues;
            this.staticValues = staticValues;
            this.formats = formats;
            this.rows = rows;
            this.rowIds = rowIds;
            this.refresh = refresh;
        }

        static RenderPlan build(long contentGeneration, long emojiGeneration, String title, List<BoardLine> content,
                                int maxLines, UnaryOperator<String> staticRender) {
            return build(contentGeneration, emojiGeneration, title, content, maxLines, staticRender, BoardLayout.Refresh.DEFAULTS);
        }

        static RenderPlan build(long contentGeneration, long emojiGeneration, String title, List<BoardLine> content,
                                int maxLines, UnaryOperator<String> staticRender, BoardLayout.Refresh refresh) {
            String rawTitle = title == null ? "" : title;
            String staticTitle = null;
            if ((TextPipeline.classify(rawTitle) & DYNAMIC_FLAGS) == 0) {
                staticTitle = renderValue(rawTitle, staticRender);
            }

            Object[] snapshot = content.toArray();
            int count = Math.min(snapshot.length, maxLines);
            String[] rawLines = new String[count];
            String[] staticLines = new String[count];
            boolean[] fastLines = new boolean[count];
            String[] rawValues = new String[count];
            String[] staticValues = new String[count];
            BoardLineFormat[] formats = new BoardLineFormat[count];
            BoardLine[] rows = new BoardLine[count];
            String[] rowIds = new String[count];
            for (int i = 0; i < count; i++) {
                BoardLine line = (BoardLine) snapshot[i];
                rows[i] = line;
                rowIds[i] = line.id() == null ? "#" + i : line.id();
                String raw = line.text();
                rawLines[i] = raw;
                staticLines[i] = (TextPipeline.classify(raw) & DYNAMIC_FLAGS) == 0
                    ? renderValue(raw, staticRender)
                    : null;
                fastLines[i] = staticLines[i] == null && TextPipeline.requiresFastRefresh(raw);
                formats[i] = line.format() == null && line.value() != null ? BoardLineFormat.FIXED : line.format();
                String value = line.value();
                rawValues[i] = value;
                staticValues[i] = value != null && (TextPipeline.classify(value) & DYNAMIC_FLAGS) == 0
                    ? renderValue(value, staticRender)
                    : null;
            }
            return new RenderPlan(contentGeneration, emojiGeneration, rawTitle, staticTitle,
                staticTitle == null && TextPipeline.requiresFastRefresh(rawTitle), rawLines, staticLines, fastLines,
                rawValues, staticValues, formats, rows, rowIds, refresh);
        }

        private static String renderValue(String raw, UnaryOperator<String> staticRender) {
            if (TextPipeline.classify(raw) == 0) {
                return raw;
            }
            String rendered = staticRender.apply(raw);
            return rendered == null ? "" : rendered;
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

        boolean fastTitle() {
            return fastTitle;
        }

        boolean fastLine(int index) {
            return fastLines[index];
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

        public String rawValue(int index) {
            return rawValues[index];
        }

        public String staticValue(int index) {
            return staticValues[index];
        }

        public BoardLineFormat format(int index) {
            return formats[index];
        }

        BoardLayout.Refresh refresh() {
            return refresh;
        }

        boolean fastValue(int index) {
            return rawValues[index] != null && staticValues[index] == null && TextPipeline.requiresFastRefresh(rawValues[index]);
        }

        String rowId(int index) {
            return rowIds[index];
        }

        int[] visibleRows(ExprScope scope, BoundedConditionErrorCallback errors, int limit) {
            int[] visible = new int[Math.min(limit, rows.length)];
            int count = 0;
            for (int index = 0; index < rows.length && count < limit; index++) {
                if (rows[index].show().matches(scope, errors)) {
                    visible[count++] = index;
                }
            }
            return count == visible.length ? visible : Arrays.copyOf(visible, count);
        }

        public boolean hasValueColumn() {
            for (int index = 0; index < formats.length; index++) {
                if (formats[index] != null || rawValues[index] != null) {
                    return true;
                }
            }
            return false;
        }
    }
}
