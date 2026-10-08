package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExpressionScope;
import art.arcane.gloss.tab.TablistLayoutDefinition;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record ProxyTabLayout(Expr show, Presentation presentation, List<Variant> variants) {
    private static final Gson GSON = new Gson();

    public static ProxyTabLayout parse(JsonObject source) {
        if (source == null || !source.has("enabled") || !source.get("enabled").getAsBoolean()) {
            return null;
        }
        List<Variant> variants = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (source.has("variants")) {
            for (JsonElement value : source.getAsJsonArray("variants")) {
                TablistLayoutDefinition.LayoutVariant variant = GSON.fromJson(value, TablistLayoutDefinition.LayoutVariant.class);
                if (!ids.add(variant.id())) {
                    throw new IllegalArgumentException("duplicate tablist layout variant id: " + variant.id());
                }
                variants.add(new Variant(variant.id(), variant.priority(), ProxyText.parseExpression(variant.when()),
                    compile(variant.presentation())));
            }
        }
        variants.sort(Comparator.comparingInt(Variant::priority).reversed().thenComparing(Variant::id));
        String show = source.has("show") && !source.get("show").isJsonNull() ? source.get("show").getAsString() : "!viewer.bedrock";
        return new ProxyTabLayout(ProxyText.parseExpression(show),
            compile(GSON.fromJson(source, TablistLayoutDefinition.LayoutPresentation.class)), List.copyOf(variants));
    }

    public Presentation select(ExpressionScope scope) {
        if (!ExprEvaluator.bool(show, scope)) {
            return null;
        }
        for (Variant variant : variants) {
            if (ExprEvaluator.bool(variant.when(), scope)) {
                return variant.presentation();
            }
        }
        return presentation;
    }

    private static Presentation compile(TablistLayoutDefinition.LayoutPresentation source) {
        List<Section> sections = new ArrayList<>(source.sections().size());
        for (TablistLayoutDefinition.Slot slot : source.slots()) {
            ProxyText.validateTemplate(slot.text());
        }
        for (TablistLayoutDefinition.Section section : source.sections()) {
            List<SortKey> sort = new ArrayList<>();
            for (TablistLayoutDefinition.SortKey key : section.sort()) {
                sort.add(new SortKey(ProxyText.parseExpression(key.expression()), key.type().equals("number"),
                    key.direction().equals("descending")));
            }
            ProxyText.validateTemplate(section.format());
            ProxyText.validateTemplate(section.overflowFormat());
            sections.add(new Section(section, ProxyText.parseExpression(section.filter()), List.copyOf(sort)));
        }
        return new Presentation(source, List.copyOf(sections));
    }

    public record Presentation(TablistLayoutDefinition.LayoutPresentation source, List<Section> sections) {
    }

    public record Section(TablistLayoutDefinition.Section source, Expr filter, List<SortKey> sort) {
    }

    public record SortKey(Expr expression, boolean numeric, boolean descending) {
    }

    public record Variant(String id, int priority, Expr when, Presentation presentation) {
    }
}
