package art.arcane.gloss.bubble;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BubbleShimmerPlan {
    static final long DEFAULT_DURATION_MS = 700L;
    static final long DEFAULT_SPAWN_DELAY_MS = 400L;
    static final long DEFAULT_FLY_AWAY_LEAD_MS = 700L;
    static final long NO_BAND = Long.MIN_VALUE;

    private final boolean spawn;
    private final boolean flyAway;
    private final String color;
    private final int width;
    private final long durationMs;
    private final long spawnDelayMs;
    private final long flyAwayLeadMs;

    private BubbleShimmerPlan(BubbleStyleDoc.Shimmer shimmer) {
        spawn = shimmer.spawn();
        flyAway = shimmer.flyAway();
        color = rgbCode(shimmer.color());
        width = shimmer.width();
        durationMs = shimmer.durationMs();
        spawnDelayMs = shimmer.spawnDelayMs();
        flyAwayLeadMs = shimmer.flyAwayLeadMs();
    }

    static BubbleShimmerPlan compile(BubbleStyleDoc.Shimmer shimmer) {
        return new BubbleShimmerPlan(shimmer);
    }

    List<String> render(List<String> lines, long ageMs, long lifetimeMs) {
        return renderAt(lines, bandIndex(ageMs, lifetimeMs, visibleCount(lines)));
    }

    List<String> renderAt(List<String> lines, long bandIndex) {
        if (bandIndex == NO_BAND) {
            return lines;
        }
        List<String> rendered = new ArrayList<>(lines.size());
        boolean touched = false;
        int visibleIndex = 0;
        for (String line : lines) {
            LineRender next = renderLine(line, bandIndex, visibleIndex);
            touched |= next.text() != line;
            rendered.add(next.text());
            visibleIndex = next.nextVisibleIndex();
        }
        return touched ? List.copyOf(rendered) : lines;
    }

    long bandIndex(long ageMs, long lifetimeMs, int visibleGlyphs) {
        if (visibleGlyphs <= 0) {
            return NO_BAND;
        }
        double progress = progress(ageMs, lifetimeMs);
        if (progress < 0.0D) {
            return NO_BAND;
        }
        return Math.round(progress * (visibleGlyphs - 1L));
    }

    int visibleCount(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            int cursor = 0;
            while (cursor < line.length()) {
                int formatLength = formatLength(line, cursor);
                if (formatLength > 0) {
                    cursor += formatLength;
                    continue;
                }
                cursor += Character.charCount(line.codePointAt(cursor));
                count++;
            }
        }
        return count;
    }

    private double progress(long ageMs, long lifetimeMs) {
        if (flyAway) {
            long departureStartMs = Math.max(0L, lifetimeMs - flyAwayLeadMs);
            double departure = progressAt(ageMs, departureStartMs);
            if (departure >= 0.0D) {
                return departure;
            }
        }
        if (spawn) {
            return progressAt(ageMs, spawnDelayMs);
        }
        return -1.0D;
    }

    private double progressAt(long ageMs, long startsAtMs) {
        long elapsedMs = ageMs - startsAtMs;
        if (elapsedMs < 0L || elapsedMs > durationMs) {
            return -1.0D;
        }
        return elapsedMs / (double) durationMs;
    }

    private boolean lit(long bandIndex, long at) {
        long distance = bandIndex - at;
        int before = (width - 1) / 2;
        int after = width - before - 1;
        return distance >= -after && distance <= before;
    }

    private LineRender renderLine(String line, long bandIndex, int firstVisibleIndex) {
        StringBuilder rendered = null;
        FormatState formatting = new FormatState();
        int visibleIndex = firstVisibleIndex;
        int cursor = 0;
        while (cursor < line.length()) {
            int formatLength = formatLength(line, cursor);
            if (formatLength > 0) {
                String raw = line.substring(cursor, cursor + formatLength);
                formatting.apply(raw);
                if (rendered != null) {
                    rendered.append(raw);
                }
                cursor += formatLength;
                continue;
            }
            int codePoint = line.codePointAt(cursor);
            int glyphLength = Character.charCount(codePoint);
            if (lit(bandIndex, visibleIndex)) {
                if (rendered == null) {
                    rendered = new StringBuilder(line.length() + 64).append(line, 0, cursor);
                }
                rendered.append(color)
                    .append(line, cursor, cursor + glyphLength)
                    .append(formatting.codes());
            } else if (rendered != null) {
                rendered.append(line, cursor, cursor + glyphLength);
            }
            visibleIndex++;
            cursor += glyphLength;
        }
        return new LineRender(rendered == null ? line : rendered.toString(), visibleIndex);
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

    private static String rgbCode(String value) {
        StringBuilder code = new StringBuilder(14).append('§').append('x');
        for (int index = 1; index < value.length(); index++) {
            code.append('§').append(value.charAt(index));
        }
        return code.toString();
    }

    private record LineRender(String text, int nextVisibleIndex) {
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
