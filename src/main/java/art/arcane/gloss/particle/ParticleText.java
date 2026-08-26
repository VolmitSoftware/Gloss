package art.arcane.gloss.particle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

public final class ParticleText {
    private static final String OPEN_PREFIX = "<particles:";
    private static final String CLOSE = "</particles>";
    private static final char MARKER = '\u0007';

    private ParticleText() {
    }

    public static Rendered render(String authored, Function<String, String> renderer) {
        Objects.requireNonNull(renderer, "particle text renderer may not be null");
        Template template = parse(authored);
        return renderMarked(template.marked(), renderer);
    }

    public static Rendered renderMarked(String marked, Function<String, String> renderer) {
        Objects.requireNonNull(renderer, "particle text renderer may not be null");
        String rendered = renderer.apply(marked == null ? "" : marked);
        return resolve(rendered == null ? "" : rendered);
    }

    public static Template parse(String authored) {
        String source = authored == null ? "" : authored;
        StringBuilder marked = new StringBuilder(source.length());
        Deque<String> open = new ArrayDeque<>();
        int cursor = 0;
        while (cursor < source.length()) {
            if (source.regionMatches(true, cursor, OPEN_PREFIX, 0, OPEN_PREFIX.length())) {
                int end = source.indexOf('>', cursor + OPEN_PREFIX.length());
                if (end < 0) {
                    throw new IllegalArgumentException("particle text span is missing its closing >");
                }
                String name = normalizeName(source.substring(cursor + OPEN_PREFIX.length(), end));
                if (!open.isEmpty()) {
                    throw new IllegalArgumentException("particle text spans may not be nested");
                }
                open.push(name);
                appendMarker(marked, true, name);
                cursor = end + 1;
                continue;
            }
            if (source.regionMatches(true, cursor, CLOSE, 0, CLOSE.length())) {
                if (open.isEmpty()) {
                    throw new IllegalArgumentException("particle text span closes without an opening tag");
                }
                appendMarker(marked, false, open.pop());
                cursor += CLOSE.length();
                continue;
            }
            marked.append(source.charAt(cursor));
            cursor++;
        }
        if (!open.isEmpty()) {
            throw new IllegalArgumentException("particle text span " + open.peek() + " is not closed");
        }
        return new Template(marked.toString());
    }

    private static Rendered resolve(String marked) {
        StringBuilder text = new StringBuilder(marked.length());
        List<Span> spans = new ArrayList<>();
        Deque<OpenSpan> open = new ArrayDeque<>();
        int cursor = 0;
        while (cursor < marked.length()) {
            if (marked.charAt(cursor) != MARKER) {
                text.append(marked.charAt(cursor));
                cursor++;
                continue;
            }
            int kindIndex = cursor + 1;
            int nameEnd = marked.indexOf(MARKER, kindIndex + 1);
            if (nameEnd < 0 || kindIndex >= marked.length()) {
                text.append(marked.charAt(cursor));
                cursor++;
                continue;
            }
            char kind = marked.charAt(kindIndex);
            String name = marked.substring(kindIndex + 1, nameEnd);
            if (kind == '+') {
                open.push(new OpenSpan(name, text.length()));
            } else if (kind == '-') {
                if (open.isEmpty() || !open.peek().name().equals(name)) {
                    throw new IllegalArgumentException("particle span markers were changed during text rendering");
                }
                OpenSpan started = open.pop();
                spans.add(new Span(name, started.start(), text.length()));
            } else {
                text.append(marked, cursor, nameEnd + 1);
            }
            cursor = nameEnd + 1;
        }
        if (!open.isEmpty()) {
            throw new IllegalArgumentException("particle span markers were removed during text rendering");
        }
        return new Rendered(text.toString(), List.copyOf(spans));
    }

    private static void appendMarker(StringBuilder output, boolean opening, String name) {
        output.append(MARKER).append(opening ? '+' : '-').append(name).append(MARKER);
    }

    private static String normalizeName(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 64 || !normalized.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException(
                "particle text span name must match [a-z0-9][a-z0-9._-]* and be at most 64 characters");
        }
        return normalized;
    }

    public record Template(String marked) {
    }

    public record Rendered(String text, List<Span> spans) {
        public Rendered {
            text = text == null ? "" : text;
            spans = spans == null ? List.of() : List.copyOf(spans);
        }

        public List<Span> named(String name) {
            String normalized = normalizeName(name);
            List<Span> matches = new ArrayList<>();
            for (Span span : spans) {
                if (span.name().equals(normalized)) {
                    matches.add(span);
                }
            }
            return List.copyOf(matches);
        }
    }

    public record Span(String name, int start, int end) {
        public Span {
            name = normalizeName(name);
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("particle text span range is invalid");
            }
        }
    }

    private record OpenSpan(String name, int start) {
    }
}
