package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public final class TextArtRenderer {
    private static final int BASE_FONT_SIZE = 12;
    private static final int MIN_FONT_SIZE = 6;
    private static final int MAX_FONT_SIZE = 32;
    private static final int LUMINANCE_THRESHOLD = 128;
    private static final char PIXEL = '█';

    private TextArtRenderer() {
    }

    public static List<String> render(String text, double scale, int maxWidth) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        try {
            return renderRows(text.trim(), fontSize(scale), Math.max(1, maxWidth));
        } catch (Throwable failure) {
            Gloss.logExceptionStack(false, failure, "Text art rasterization failed.");
            return List.of();
        }
    }

    private static int fontSize(double scale) {
        double safeScale = Double.isFinite(scale) && scale > 0.0D ? scale : 1.0D;
        int size = (int) Math.round(BASE_FONT_SIZE * safeScale);
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }

    private static List<String> renderRows(String text, int fontSize, int maxWidth) {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D probeGraphics = probe.createGraphics();
        probeGraphics.setFont(font);
        FontMetrics metrics = probeGraphics.getFontMetrics();
        int width = Math.max(1, metrics.stringWidth(text));
        int height = Math.max(1, metrics.getHeight());
        int ascent = metrics.getAscent();
        probeGraphics.dispose();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.WHITE);
        graphics.setFont(font);
        graphics.drawString(text, 0, ascent);
        graphics.dispose();

        boolean[][] grid = toGrid(image);
        return toRows(trim(grid, maxWidth));
    }

    private static boolean[][] toGrid(BufferedImage image) {
        boolean[][] grid = new boolean[image.getHeight()][image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                grid[y][x] = (red + green + blue) / 3 > LUMINANCE_THRESHOLD;
            }
        }
        return grid;
    }

    private static boolean[][] trim(boolean[][] grid, int maxWidth) {
        int top = grid.length;
        int bottom = -1;
        int left = grid.length == 0 ? 0 : grid[0].length;
        int right = -1;
        for (int y = 0; y < grid.length; y++) {
            for (int x = 0; x < grid[y].length; x++) {
                if (!grid[y][x]) {
                    continue;
                }
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }

        if (bottom < 0) {
            return new boolean[0][0];
        }

        int trimmedWidth = Math.min(right - left + 1, maxWidth);
        boolean[][] trimmed = new boolean[bottom - top + 1][trimmedWidth];
        for (int y = 0; y < trimmed.length; y++) {
            for (int x = 0; x < trimmedWidth; x++) {
                trimmed[y][x] = grid[top + y][left + x];
            }
        }
        return trimmed;
    }

    private static List<String> toRows(boolean[][] grid) {
        List<String> rows = new ArrayList<>(grid.length);
        for (boolean[] gridRow : grid) {
            StringBuilder row = new StringBuilder(gridRow.length);
            for (boolean set : gridRow) {
                row.append(set ? PIXEL : ' ');
            }
            int end = row.length();
            while (end > 0 && row.charAt(end - 1) == ' ') {
                end--;
            }
            rows.add(row.substring(0, end));
        }
        return List.copyOf(rows);
    }
}
