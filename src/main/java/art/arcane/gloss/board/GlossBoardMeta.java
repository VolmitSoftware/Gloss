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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

public final class GlossBoardMeta {
    private final String id;
    private final CopyOnWriteArrayList<String> content;
    private final AtomicLong contentGeneration;
    private final Map<String, RenderPlan> renderPlans;
    private volatile String title;
    private volatile boolean hideNumbers;
    private volatile ShowCondition show = ShowCondition.ALWAYS;
    private volatile BoardDoc.Selection selection;
    private volatile List<BoardDoc.Variant> variants;
    private volatile CompiledCondition selectionCondition;
    private volatile List<CompiledVariant> compiledVariants;
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
        meta.setTitle(presentation.title().isEmpty() ? id : presentation.title());
        for (String line : presentation.lines()) {
            meta.addLine(line);
        }
        meta.setHideNumbers(presentation.hideNumbers());
        meta.setShow(doc.show());
        meta.setSelection(doc.select().priority(), doc.select().when());
        meta.setVariants(doc.variants());
        meta.revision = doc.revision();
        return meta;
    }

    public BoardDoc toDoc(long revision) {
        return new BoardDoc(BoardDoc.CURRENT_SCHEMA_VERSION, revision, show, selection, presentation(), variants);
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

    public List<String> lines() {
        return List.copyOf(content);
    }

    public void addLine(String line) {
        content.add(line == null ? "" : line);
        contentChanged();
    }

    public void setLine(int index, String line) {
        content.set(index, line == null ? "" : line);
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
        renderPlans.clear();
    }

    boolean matchesSelection(ExprScope scope, BoundedConditionErrorCallback errors) {
        return show.matches(scope, errors) && selectionCondition.matches(scope, errors);
    }

    ActiveProfile activeProfile(ExprScope scope, BoundedConditionErrorCallback errors) {
        for (CompiledVariant variant : compiledVariants) {
            if (variant.condition().matches(scope, errors)) {
                return new ActiveProfile(variant.variant().id(), variant.variant().presentation());
            }
        }
        return new ActiveProfile("base", presentation());
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
    BoardDoc.Presentation presentation() {
        return new BoardDoc.Presentation(title, lines(), hideNumbers);
    }

    RenderPlan renderPlan(String profileId, BoardDoc.Presentation presentation, long emojiGeneration,
                          int maxLines, UnaryOperator<String> staticRender) {
        RenderPlan current = renderPlans.get(profileId);
        long generation = contentGeneration.get();
        if (current != null && current.matches(generation, emojiGeneration)) {
            return current;
        }
        RenderPlan built = RenderPlan.build(generation, emojiGeneration, presentation.title(),
            presentation.lines(), maxLines, staticRender);
        renderPlans.put(profileId, built);
        return built;
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
            compiled.add(new CompiledVariant(value, condition));
        }
        compiled.sort(Comparator
            .comparingInt((CompiledVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));
        return List.copyOf(compiled);
    }

    record ActiveProfile(String id, BoardDoc.Presentation presentation) {
    }

    private record CompiledVariant(BoardDoc.Variant variant, CompiledCondition condition) {
    }

    /**
     * Immutable per-board render memo. {@code staticTitle}/{@code staticLines} entries are
     * pre-rendered; a {@code null} entry means the value
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
                                int maxLines, UnaryOperator<String> staticRender) {
            String rawTitle = title == null ? "" : title;
            String staticTitle = null;
            if ((TextPipeline.classify(rawTitle) & DYNAMIC_FLAGS) == 0) {
                staticTitle = renderValue(rawTitle, staticRender);
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
