package art.arcane.gloss.particle;

import java.util.ArrayList;
import java.util.List;

public final class ParticleTextLayout {
    private static final double DEFAULT_CHARACTER_WIDTH = 0.1D;
    private static final double DEFAULT_LINE_HEIGHT = 0.26D;

    private ParticleTextLayout() {
    }

    public static List<ParticleRect> bounds(ParticleText.Rendered rendered, String spanName,
                                            double scale, boolean perLetter) {
        List<ParticleText.Span> spans = rendered.named(spanName);
        if (spans.isEmpty()) {
            return List.of();
        }
        Layout layout = layout(rendered.text(), scale);
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

    public static ParticleRect textBounds(String rendered, double scale) {
        return layout(rendered, scale).bounds();
    }

    public static List<ParticleRect> lineBounds(String rendered, double scale) {
        Layout layout = layout(rendered, scale);
        int lineCount = Math.max(1, countLines(rendered));
        double lineHeight = DEFAULT_LINE_HEIGHT * scale;
        List<ParticleRect> lines = new ArrayList<>(lineCount);
        for (int line = 0; line < lineCount; line++) {
            double minimumX = Double.POSITIVE_INFINITY;
            double maximumX = Double.NEGATIVE_INFINITY;
            double centerY = (lineCount - 1) * lineHeight / 2.0D - line * lineHeight;
            for (Cell cell : layout.cells()) {
                if (Math.abs(cell.bounds().centerY() - centerY) > 1.0E-9D) {
                    continue;
                }
                minimumX = Math.min(minimumX, cell.bounds().centerX() - cell.bounds().width() / 2.0D);
                maximumX = Math.max(maximumX, cell.bounds().centerX() + cell.bounds().width() / 2.0D);
            }
            double width = minimumX == Double.POSITIVE_INFINITY ? 0.0D : maximumX - minimumX;
            lines.add(new ParticleRect(0.0D, centerY, 0.0D, width, lineHeight, 0.0D));
        }
        return List.copyOf(lines);
    }

    private static Layout layout(String rendered, double scale) {
        double safeScale = Double.isFinite(scale) ? Math.max(0.0D, scale) : 1.0D;
        double characterWidth = DEFAULT_CHARACTER_WIDTH * safeScale;
        double lineHeight = DEFAULT_LINE_HEIGHT * safeScale;
        List<CellDraft> drafts = new ArrayList<>();
        List<Integer> lineWidths = new ArrayList<>();
        int line = 0;
        int column = 0;
        int index = 0;
        while (index < rendered.length()) {
            char value = rendered.charAt(index);
            if ((value == '\u00a7' || value == '&') && index + 1 < rendered.length()
                && isLegacyCode(rendered.charAt(index + 1))) {
                index += legacyCodeLength(rendered, index);
                continue;
            }
            if (value == '\n' || value == '\r' || value == '\u2028' || value == '\u2029') {
                lineWidths.add(column);
                line++;
                column = 0;
                if (value == '\r' && index + 1 < rendered.length() && rendered.charAt(index + 1) == '\n') {
                    index++;
                }
                index++;
                continue;
            }
            if (!Character.isISOControl(value)) {
                drafts.add(new CellDraft(index, line, column));
                column++;
            }
            index++;
        }
        lineWidths.add(column);
        int lineCount = lineWidths.size();
        List<Cell> cells = new ArrayList<>(drafts.size());
        double maximumWidth = 0.0D;
        for (int width : lineWidths) {
            maximumWidth = Math.max(maximumWidth, width * characterWidth);
        }
        for (CellDraft draft : drafts) {
            double lineWidth = lineWidths.get(draft.line()) * characterWidth;
            double x = -lineWidth / 2.0D + characterWidth * (draft.column() + 0.5D);
            double y = (lineCount - 1) * lineHeight / 2.0D - draft.line() * lineHeight;
            cells.add(new Cell(draft.sourceIndex(),
                new ParticleRect(x, y, 0.0D, characterWidth, lineHeight, 0.0D)));
        }
        return new Layout(List.copyOf(cells),
            ParticleRect.plane(maximumWidth, lineCount * lineHeight));
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

    private static int countLines(String rendered) {
        int count = 1;
        for (int index = 0; index < rendered.length(); index++) {
            char value = rendered.charAt(index);
            if (value == '\n' || value == '\u2028' || value == '\u2029') {
                count++;
            } else if (value == '\r') {
                count++;
                if (index + 1 < rendered.length() && rendered.charAt(index + 1) == '\n') {
                    index++;
                }
            }
        }
        return count;
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

    private record Layout(List<Cell> cells, ParticleRect bounds) {
    }

    private record CellDraft(int sourceIndex, int line, int column) {
    }

    private record Cell(int sourceIndex, ParticleRect bounds) {
    }
}
