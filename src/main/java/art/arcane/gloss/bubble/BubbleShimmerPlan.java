package art.arcane.gloss.bubble;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BubbleShimmerPlan {
    private final boolean spawn;
    private final boolean flyAway;
    private final String color;
    private final int width;
    private final long durationMs;
    private final long spawnDelayMs;
    private final long flyAwayLeadMs;

    private BubbleShimmerPlan(BubbleStyleDoc.Shimmer shimmer) {
        this.spawn = shimmer.spawn();
        this.flyAway = shimmer.flyAway();
        this.color = rgbCode(shimmer.color());
        this.width = shimmer.width();
        this.durationMs = shimmer.durationMs();
        this.spawnDelayMs = shimmer.spawnDelayMs();
        this.flyAwayLeadMs = shimmer.flyAwayLeadMs();
    }

    static BubbleShimmerPlan compile(BubbleStyleDoc.Shimmer shimmer) {
        return new BubbleShimmerPlan(shimmer);
    }

    List<String> render(List<String> lines, long ageMs, long lifetimeMs) {
        double progress = progress(ageMs, lifetimeMs);
        if (progress < 0.0D) {
            return lines;
        }
        List<String> rendered = new ArrayList<>(lines.size());
        for (String line : lines) {
            rendered.add(renderLine(line, progress));
        }
        return List.copyOf(rendered);
    }

    double progress(long ageMs, long lifetimeMs) {
        if (flyAway) {
            long flyAwayStartMs = Math.max(0L, lifetimeMs - flyAwayLeadMs);
            double departure = phaseProgress(ageMs, flyAwayStartMs);
            if (departure >= 0.0D) {
                return departure;
            }
        }
        return spawn ? phaseProgress(ageMs, spawnDelayMs) : -1.0D;
    }

    private double phaseProgress(long ageMs, long startMs) {
        long elapsedMs = ageMs - startMs;
        if (elapsedMs < 0L || elapsedMs > durationMs) {
            return -1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (double) elapsedMs / durationMs));
    }

    private String renderLine(String line, double progress) {
        int visibleCount = visibleCount(line);
        if (visibleCount == 0) {
            return line;
        }
        double center = progress * (visibleCount - 1);
        int first = (int) Math.round(center - ((width - 1) / 2.0D));
        int last = first + width - 1;
        StringBuilder rendered = new StringBuilder(line.length() + width * 24);
        FormatState formatting = new FormatState();
        int visibleIndex = 0;
        int cursor = 0;
        while (cursor < line.length()) {
            int formatLength = formatLength(line, cursor);
            if (formatLength > 0) {
                String raw = line.substring(cursor, cursor + formatLength);
                formatting.apply(raw);
                rendered.append(raw);
                cursor += formatLength;
                continue;
            }
            int codePoint = line.codePointAt(cursor);
            String glyph = new String(Character.toChars(codePoint));
            if (visibleIndex >= first && visibleIndex <= last) {
                rendered.append(color).append(glyph).append(formatting.codes());
            } else {
                rendered.append(glyph);
            }
            visibleIndex++;
            cursor += Character.charCount(codePoint);
        }
        return rendered.toString();
    }

    private static int visibleCount(String line) {
        int count = 0;
        int cursor = 0;
        while (cursor < line.length()) {
            int formatLength = formatLength(line, cursor);
            if (formatLength > 0) {
                cursor += formatLength;
                continue;
            }
            int codePoint = line.codePointAt(cursor);
            count++;
            cursor += Character.charCount(codePoint);
        }
        return count;
    }

    private static int formatLength(String input, int index) {
        if (isRgb(input, index)) {
            return 14;
        }
        if (index + 1 >= input.length() || input.charAt(index) != '§'
            || !isLegacyCode(input.charAt(index + 1))) {
            return 0;
        }
        return 2;
    }

    private static boolean isRgb(String input, int index) {
        if (index + 14 > input.length() || input.charAt(index) != '§'
            || Character.toLowerCase(input.charAt(index + 1)) != 'x') {
            return false;
        }
        for (int offset = 2; offset < 14; offset += 2) {
            if (input.charAt(index + offset) != '§' || !isHex(input.charAt(index + offset + 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLegacyCode(char value) {
        char normalized = Character.toLowerCase(value);
        return isHex(normalized) || normalized == 'k' || normalized == 'l' || normalized == 'm'
            || normalized == 'n' || normalized == 'o' || normalized == 'r';
    }

    private static boolean isHex(char value) {
        char normalized = Character.toLowerCase(value);
        return normalized >= '0' && normalized <= '9' || normalized >= 'a' && normalized <= 'f';
    }

    private static String rgbCode(String color) {
        StringBuilder code = new StringBuilder(14).append('§').append('x');
        for (int index = 1; index < color.length(); index++) {
            code.append('§').append(color.charAt(index));
        }
        return code.toString();
    }

    private static final class FormatState {
        private final Map<Character, String> decorations = new LinkedHashMap<>();
        private String color;

        private void apply(String raw) {
            char code = Character.toLowerCase(raw.charAt(1));
            if (code == 'x' || isHex(code)) {
                color = raw;
                decorations.clear();
                return;
            }
            if (code == 'r') {
                color = null;
                decorations.clear();
                return;
            }
            decorations.put(code, raw);
        }

        private String codes() {
            StringBuilder codes = new StringBuilder();
            codes.append(color == null ? "§r" : color);
            for (String decoration : decorations.values()) {
                codes.append(decoration);
            }
            return codes.toString();
        }
    }
}
