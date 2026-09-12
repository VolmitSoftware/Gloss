package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** One document compiled once per revision: entry conditions, chat patterns and resolved action lists. */
public record BehaviorRuntime(String id, BehaviorDoc doc, List<CompiledEntry> entries) {
    public BehaviorRuntime {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(doc, "doc");
        entries = List.copyOf(entries);
    }

    public static BehaviorRuntime compile(String id, BehaviorDoc doc) {
        List<CompiledEntry> entries = new ArrayList<>(doc.on().size());
        for (int index = 0; index < doc.on().size(); index++) {
            BehaviorEntry entry = doc.on().get(index);
            String path = "behaviors/" + id + ".on[" + index + "]";
            CompiledCondition when = entry.when() == null ? null
                : ConditionCompiler.compile(new ConditionSource(path + ".when", entry.when()));
            Pattern pattern = entry.pattern() == null ? null : Pattern.compile(entry.pattern());
            List<MenuAction<?>> actions = MenuAction.resolve(entry.actions(), "behavior:" + id, "on:" + index);
            BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.bounded(1, error ->
                Gloss.logExceptionStack(false, error.cause(), "Behavior condition %s failed and was treated as false.",
                    error.source()));
            entries.add(new CompiledEntry(index, entry, when, pattern, actions, errors));
        }
        return new BehaviorRuntime(id, doc, entries);
    }

    public String menuId() {
        return "behavior:" + id;
    }

    public record CompiledEntry(int index, BehaviorEntry entry, CompiledCondition when, Pattern pattern,
                                List<MenuAction<?>> actions, BoundedConditionErrorCallback errors) {
        public CompiledEntry {
            Objects.requireNonNull(entry, "entry");
            actions = List.copyOf(actions);
            errors = errors == null ? BoundedConditionErrorCallback.silent() : errors;
        }

        public String componentId() {
            return "on:" + index;
        }
    }
}
