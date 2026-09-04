package art.arcane.gloss.condition;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.expr.ExprScope;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Objects;

@JsonAdapter(ShowCondition.Adapter.class)
public final class ShowCondition {
    public static final ShowCondition ALWAYS = new ShowCondition("true");
    public static final ShowCondition NEVER = new ShowCondition("false");

    private final String expression;
    private final CompiledCondition condition;
    private final Boolean constant;
    private final BoundedConditionErrorCallback errors;

    private ShowCondition(String expression) {
        this.expression = expression;
        this.condition = ConditionCompiler.compile(new ConditionSource("show", expression));
        this.constant = switch (expression) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
        this.errors = BoundedConditionErrorCallback.bounded(1, error ->
            Gloss.logExceptionStack(false, error.cause(),
                "Show condition %s failed and was treated as false.", error.source()));
    }

    public static ShowCondition of(String expression) {
        String normalized = Objects.requireNonNull(expression, "show").trim();
        if (normalized.startsWith("{{") && normalized.endsWith("}}")) {
            normalized = normalized.substring(2, normalized.length() - 2).trim();
        }
        return switch (normalized) {
            case "true" -> ALWAYS;
            case "false" -> NEVER;
            default -> new ShowCondition(normalized);
        };
    }

    public String expression() {
        return expression;
    }

    public boolean isAlwaysVisible() {
        return Boolean.TRUE.equals(constant);
    }

    public boolean isDynamic() {
        return constant == null;
    }

    public boolean matches(ExprScope scope) {
        return matches(scope, errors);
    }

    public boolean matches(ExprScope scope, BoundedConditionErrorCallback errors) {
        return constant == null ? condition.matches(scope, errors) : constant;
    }

    public boolean matches(Gloss plugin, Player viewer) {
        if (constant != null) {
            return constant;
        }
        GlossConditionContext context = viewer == null
            ? GlossConditionContext.subject(null) : GlossConditionContext.viewer(viewer);
        return matches(new GlossConditionScope(plugin, context));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShowCondition show && expression.equals(show.expression);
    }

    @Override
    public int hashCode() {
        return expression.hashCode();
    }

    public static final class Adapter extends TypeAdapter<ShowCondition> {
        @Override
        public ShowCondition read(JsonReader reader) throws IOException {
            JsonToken token = reader.peek();
            if (token == JsonToken.BOOLEAN) {
                return reader.nextBoolean() ? ALWAYS : NEVER;
            }
            if (token == JsonToken.STRING) {
                return of(reader.nextString());
            }
            throw new IllegalArgumentException("show must be a boolean or boolean expression at " + reader.getPath());
        }

        @Override
        public void write(JsonWriter writer, ShowCondition show) throws IOException {
            if (show.constant != null) {
                writer.value(show.constant);
            } else {
                writer.value(show.expression);
            }
        }
    }
}
