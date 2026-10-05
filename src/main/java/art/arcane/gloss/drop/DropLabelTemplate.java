package art.arcane.gloss.drop;

import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.hologram.AnimationTemplate;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

final class DropLabelTemplate {
    private final Map<String, String> tokens = new LinkedHashMap<>();
    private final Map<String, String> replacements = new LinkedHashMap<>();

    String literal(String text) {
        String token = tokens.get(text);
        if (token == null) {
            token = "\uE100" + tokens.size() + "\uE101";
            tokens.put(text, token);
            replacements.put(token, text);
        }
        return token;
    }

    String name(String token) {
        return replacements.getOrDefault(token, token);
    }

    ParticleText.Rendered render(String authored, Function<String, ParticleText.Rendered> renderer) {
        return replace(renderer.apply(authored));
    }

    Frames frames(AnimationTemplate animation) {
        return new Frames(this, animation);
    }

    ParticleText.Rendered replace(ParticleText.Rendered rendered) {
        if (tokens.isEmpty()) {
            return rendered;
        }
        String source = rendered.text();
        int[] offsets = new int[source.length() + 1];
        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        while (cursor < source.length()) {
            offsets[cursor] = result.length();
            int end = source.charAt(cursor) == '\uE100' ? source.indexOf('\uE101', cursor + 1) : -1;
            String replacement = end < 0 ? null : replacements.get(source.substring(cursor, end + 1));
            if (replacement == null) {
                result.append(source.charAt(cursor++));
                continue;
            }
            for (int index = cursor + 1; index <= end; index++) {
                offsets[index] = result.length();
            }
            result.append(replacement).append(ChatColor.RESET)
                .append(ChatColor.getLastColors(source.substring(0, cursor)));
            cursor = end + 1;
        }
        offsets[source.length()] = result.length();
        List<ParticleText.Span> spans = new ArrayList<>(rendered.spans().size());
        for (ParticleText.Span span : rendered.spans()) {
            spans.add(new ParticleText.Span(span.name(), offsets[span.start()], offsets[span.end()]));
        }
        return new ParticleText.Rendered(result.toString(), List.copyOf(spans));
    }

    static final class Frames implements LongFunction<String> {
        private final DropLabelTemplate names;
        private final AnimationTemplate animation;
        private String previous;
        private ParticleText.Rendered rendered;

        private Frames(DropLabelTemplate names, AnimationTemplate animation) {
            this.names = names;
            this.animation = animation;
        }

        @Override
        public String apply(long now) {
            return render(now).text();
        }

        synchronized ParticleText.Rendered render(long now) {
            String composed = animation.compose(now);
            if (!composed.equals(previous)) {
                rendered = names.replace(ParticleText.renderLegacyMarked(composed, UnaryOperator.identity()));
                previous = composed;
            }
            return rendered;
        }
    }
}
