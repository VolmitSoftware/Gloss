package art.arcane.gloss.particle;

import art.arcane.gloss.text.TextDisplayLayout;
import art.arcane.gloss.api.IconTextAlignment;
import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;

import java.util.ArrayList;
import java.util.List;

public final class ParticleTextLayout {
    private static final double DEFAULT_LINE_HEIGHT = TextDisplayLayout.ROW_PIXELS * (double) TextDisplayLayout.PIXEL_SIZE;
    private static final int MAX_CACHED_LAYOUTS = 512;
    private static final TextStyle DEFAULT_STYLE = new TextStyle(TextDisplayLayout.FULL_WIDTH, IconTextAlignment.CENTER);

    /**
     * Every viewer of a hologram lays out the same rendered string on every tick, so the per
     * character pass and its derived rectangles are memoized by the text they came from.
     */
    private static final BoundedCache<LayoutKey, Layout> LAYOUTS = new BoundedCache<>(MAX_CACHED_LAYOUTS);
    private static final BoundedCache<LayoutKey, List<ParticleRect>> LINES =
        new BoundedCache<>(MAX_CACHED_LAYOUTS);
    private static final BoundedCache<SpanKey, List<ParticleRect>> SPANS =
        new BoundedCache<>(MAX_CACHED_LAYOUTS);

    private ParticleTextLayout() {
    }

    public static List<ParticleRect> bounds(ParticleText.Rendered rendered, String spanName,
                                            double scale, boolean perLetter) {
        return SPANS.get(new SpanKey(rendered.text(), rendered.spans(), spanName, scale, perLetter, DEFAULT_STYLE),
            key -> spanBounds(rendered, key.name(), key.scale(), key.perLetter(), key.style()));
    }

    public static ParticleRect textBounds(String rendered, double scale) {
        return layout(rendered, scale, DEFAULT_STYLE).bounds();
    }

    public static List<ParticleRect> lineBounds(String rendered, double scale) {
        return LINES.get(new LayoutKey(rendered, scale, DEFAULT_STYLE), key -> computeLineBounds(key.text(), key.scale(), key.style()));
    }

    public static ParticleRect styledTextBounds(String rendered, double scale, TextStyle style) {
        return layout(rendered, scale, style).bounds();
    }

    public static List<ParticleRect> styledLineBounds(String rendered, double scale, TextStyle style) {
        return LINES.get(new LayoutKey(rendered, scale, style),
            key -> computeLineBounds(key.text(), key.scale(), key.style()));
    }

    public static List<ParticleRect> styledBounds(ParticleText.Rendered rendered, String spanName,
                                                 double scale, boolean perLetter, TextStyle style) {
        return SPANS.get(new SpanKey(rendered.text(), rendered.spans(), spanName, scale, perLetter, style),
            key -> spanBounds(rendered, key.name(), key.scale(), key.perLetter(), key.style()));
    }

    static void clearCaches() {
        LAYOUTS.clear();
        LINES.clear();
        SPANS.clear();
    }

    private static List<ParticleRect> spanBounds(ParticleText.Rendered rendered, String spanName,
                                                 double scale, boolean perLetter, TextStyle style) {
        List<ParticleText.Span> spans = rendered.named(spanName);
        if (spans.isEmpty()) {
            return List.of();
        }
        Layout layout = layout(rendered.text(), scale, style);
        List<ParticleRect> bounds = new ArrayList<>();
        for (ParticleText.Span span : spans) {
            List<Cell> cells = new ArrayList<>();
            for (Cell cell : layout.cells()) {
                if (cell.sourceIndex() >= span.start() && cell.sourceIndex() < span.end()) {
                    cells.add(cell);
                }
            }
            if (perLetter) {
                for (Cell cell : cells) {
                    bounds.add(cell.bounds());
                }
            } else if (!cells.isEmpty()) {
                bounds.add(union(cells));
            }
        }
        return List.copyOf(bounds);
    }

    private static List<ParticleRect> computeLineBounds(String rendered, double scale, TextStyle style) {
        Layout layout = layout(rendered, scale, style);
        int lineCount = layout.lineCount();
        double lineHeight = DEFAULT_LINE_HEIGHT * scale;
        List<ParticleRect> lines = new ArrayList<>(lineCount);
        for (int line = 0; line < lineCount; line++) {
            double minimumX = Double.POSITIVE_INFINITY;
            double maximumX = Double.NEGATIVE_INFINITY;
            double centerY = layout.bounds().height() / 2D - (line + 0.5D) * lineHeight;
            for (Cell cell : layout.cells()) {
                if (Math.abs(cell.bounds().centerY() - centerY) > 1.0E-9D) {
                    continue;
                }
                minimumX = Math.min(minimumX, cell.bounds().centerX() - cell.bounds().width() / 2.0D);
                maximumX = Math.max(maximumX, cell.bounds().centerX() + cell.bounds().width() / 2.0D);
            }
            double width = minimumX == Double.POSITIVE_INFINITY ? 0.0D : maximumX - minimumX;
            lines.add(new ParticleRect(width == 0D ? 0D : (minimumX + maximumX) / 2D, centerY, 0.0D, width, lineHeight, 0.0D));
        }
        return List.copyOf(lines);
    }

    private static Layout layout(String rendered, double scale, TextStyle style) {
        return LAYOUTS.get(new LayoutKey(rendered, scale, style),
            key -> computeLayout(key.text(), key.scale(), key.style()));
    }

    private static Layout computeLayout(String rendered, double scale, TextStyle style) {
        double safeScale = Double.isFinite(scale) ? Math.max(0.0D, scale) : 1.0D;
        double pixel = TextDisplayLayout.PIXEL_SIZE * safeScale;
        double lineHeight = DEFAULT_LINE_HEIGHT * safeScale;
        List<CellDraft> drafts = new ArrayList<>();
        List<Integer> lineWidths = new ArrayList<>();
        int line = 0;
        int column = 0;
        boolean bold = false;
        int lineStart = 0;
        int lastSpace = -1;
        int index = 0;
        while (index < rendered.length()) {
            char value = rendered.charAt(index);
            if ((value == '\u00a7' || value == '&') && index + 1 < rendered.length()
                && isLegacyCode(rendered.charAt(index + 1))) {
                char code = Character.toLowerCase(rendered.charAt(index + 1));
                if (code == 'l') {
                    bold = true;
                } else if ("0123456789abcdefrx".indexOf(code) >= 0) {
                    bold = false;
                }
                index += legacyCodeLength(rendered, index);
                continue;
            }
            if (value == '\n' || value == '\r' || value == '\u2028' || value == '\u2029') {
                lineWidths.add(column);
                line++;
                column = 0;
                lineStart = drafts.size();
                lastSpace = -1;
                if (value == '\r' && index + 1 < rendered.length() && rendered.charAt(index + 1) == '\n') {
                    index++;
                }
                index++;
                continue;
            }
            if (!Character.isISOControl(value)) {
                MapFont.CharacterSprite glyph = MinecraftFont.Font.getChar(value);
                int advance = (glyph == null ? 5 : glyph.getWidth()) + 1 + (bold ? 1 : 0);
                if (column > 0 && column + advance > style.lineWidth()) {
                    if (lastSpace >= lineStart) {
                        CellDraft space = drafts.get(lastSpace);
                        int nextColumn = space.column() + space.width();
                        lineWidths.add(space.column());
                        drafts.remove(lastSpace);
                        line++;
                        for (int cell = lastSpace; cell < drafts.size(); cell++) {
                            CellDraft moved = drafts.get(cell);
                            drafts.set(cell, new CellDraft(moved.sourceIndex(), line,
                                moved.column() - nextColumn, moved.width()));
                        }
                        column -= nextColumn;
                        lineStart = lastSpace;
                    } else {
                        lineWidths.add(column);
                        line++;
                        column = 0;
                        lineStart = drafts.size();
                    }
                    lastSpace = -1;
                    if (value == ' ') {
                        index++;
                        continue;
                    }
                }
                if (value == ' ') {
                    lastSpace = drafts.size();
                }
                drafts.add(new CellDraft(index, line, column, advance));
                column += advance;
            }
            index++;
        }
        lineWidths.add(column);
        int lineCount = lineWidths.size();
        List<Cell> cells = new ArrayList<>(drafts.size());
        double maximumWidth = 0.0D;
        for (int width : lineWidths) {
            maximumWidth = Math.max(maximumWidth, width * pixel);
        }
        for (CellDraft draft : drafts) {
            double lineWidth = lineWidths.get(draft.line()) * pixel;
            double startX = switch (style.alignment()) {
                case CENTER -> -lineWidth / 2D;
                case LEFT -> -maximumWidth / 2D;
                case RIGHT -> maximumWidth / 2D - lineWidth;
            };
            double x = startX + pixel * (1D + draft.column() + draft.width() / 2D);
            double y = lineCount * lineHeight / 2D - (draft.line() + 0.5D) * lineHeight;
            cells.add(new Cell(draft.sourceIndex(),
                new ParticleRect(x, y, 0.0D, draft.width() * pixel, lineHeight, 0.0D)));
        }
        return new Layout(List.copyOf(cells),
            new ParticleRect(pixel / 2D, 0D, 0D, maximumWidth + pixel, lineCount * lineHeight, 0D), lineCount);
    }

    private static int legacyCodeLength(String rendered, int index) {
        if (index + 13 < rendered.length() && Character.toLowerCase(rendered.charAt(index + 1)) == 'x') {
            return 14;
        }
        return 2;
    }

    private static boolean isLegacyCode(char value) {
        return "0123456789abcdefklmnorx".indexOf(Character.toLowerCase(value)) >= 0;
    }

    private static ParticleRect union(List<Cell> cells) {
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (Cell cell : cells) {
            ParticleRect bounds = cell.bounds();
            minimumX = Math.min(minimumX, bounds.centerX() - bounds.width() / 2.0D);
            minimumY = Math.min(minimumY, bounds.centerY() - bounds.height() / 2.0D);
            maximumX = Math.max(maximumX, bounds.centerX() + bounds.width() / 2.0D);
            maximumY = Math.max(maximumY, bounds.centerY() + bounds.height() / 2.0D);
        }
        return new ParticleRect((minimumX + maximumX) / 2.0D, (minimumY + maximumY) / 2.0D, 0.0D,
            maximumX - minimumX, maximumY - minimumY, 0.0D);
    }

    private record Layout(List<Cell> cells, ParticleRect bounds, int lineCount) {
    }

    private record LayoutKey(String text, double scale, TextStyle style) {
    }

    private record SpanKey(String text, List<ParticleText.Span> spans, String name, double scale,
                           boolean perLetter, TextStyle style) {
    }

    public record TextStyle(int lineWidth, IconTextAlignment alignment) {
        public TextStyle {
            lineWidth = Math.max(1, lineWidth);
            alignment = alignment == null ? IconTextAlignment.CENTER : alignment;
        }
    }

    private record CellDraft(int sourceIndex, int line, int column, int width) {
    }

    private record Cell(int sourceIndex, ParticleRect bounds) {
    }
}
