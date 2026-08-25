package art.arcane.gloss.bubble;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public final class BubbleStyles {
    public static final String DEFAULT_STYLE_ID = "default";
    public static final String STYLE_PERMISSION_PREFIX = "gloss.bubbles.style.";

    private BubbleStyles() {
    }

    static Map<String, CompiledStyle> compile(Map<String, BubbleStyleDoc> styles) {
        Map<String, CompiledStyle> compiled = new HashMap<>(styles.size());
        for (Map.Entry<String, BubbleStyleDoc> entry : styles.entrySet()) {
            BubbleStyleDoc.Select select = entry.getValue().select();
            CompiledCondition condition = select == null ? null : ConditionCompiler.compile(new ConditionSource(
                "bubbles/" + entry.getKey() + ".select.when", select.when()));
            compiled.put(entry.getKey(), new CompiledStyle(entry.getValue(), condition));
        }
        return Map.copyOf(compiled);
    }

    static String resolveStyleId(String chosenId, Predicate<String> permissionTest,
                                 Map<String, CompiledStyle> styles, ExprScope scope,
                                 BoundedConditionErrorCallback errors) {
        if (chosenId != null && styles.containsKey(chosenId)
            && permissionTest.test(STYLE_PERMISSION_PREFIX + chosenId)) {
            return chosenId;
        }
        String matched = null;
        int matchedPriority = Integer.MIN_VALUE;
        for (Map.Entry<String, CompiledStyle> entry : styles.entrySet()) {
            BubbleStyleDoc.Select select = entry.getValue().document().select();
            CompiledCondition condition = entry.getValue().condition();
            if (select == null || condition == null || !condition.matches(scope, errors)) {
                continue;
            }
            if (matched == null || select.priority() > matchedPriority
                || select.priority() == matchedPriority && entry.getKey().compareTo(matched) < 0) {
                matched = entry.getKey();
                matchedPriority = select.priority();
            }
        }
        if (matched != null) {
            return matched;
        }
        return styles.containsKey(DEFAULT_STYLE_ID) ? DEFAULT_STYLE_ID : null;
    }

    record CompiledStyle(BubbleStyleDoc document, CompiledCondition condition) {
    }
}
