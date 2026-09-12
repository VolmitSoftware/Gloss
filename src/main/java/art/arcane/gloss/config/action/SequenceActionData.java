package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.SequenceMenuAction;
import art.arcane.gloss.state.StateSchema;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A timed list: each step is an ordinary action that may carry {@code atTicks}, its cue relative
 * to the sequence start; steps without a cue run with the previous one. {@code skippable} lets the
 * viewer end it by sneaking (running {@code onSkip}); {@code once} names a player boolean state key
 * that is set on completion and skips the sequence when already true.
 */
public record SequenceActionData(List<Step> steps, Boolean skippable, String once, List<MenuActionData> onSkip,
                                 HoloClickTrigger trigger, String when, Integer cooldownTicks) implements MenuActionData {
  public SequenceActionData {
    steps = steps == null ? List.of() : List.copyOf(steps.stream().filter(step -> step != null).toList());
    onSkip = ControlFlowLists.copy(onSkip);
    once = once == null || once.isBlank() ? null : once.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.SEQUENCE;
  }

  public boolean skippableOrDefault() {
    return skippable != null && skippable;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new SequenceMenuAction(this);
  }

  @Override
  public List<MenuActionData> nestedActions() {
    List<MenuActionData> nested = new ArrayList<>(steps.size() + onSkip.size());
    for (Step step : steps) {
      nested.add(step.action());
    }
    nested.addAll(onSkip);
    return List.copyOf(nested);
  }

  @Override
  public String invalidReason() {
    if (steps.isEmpty()) {
      return "declares no sequence steps";
    }
    for (Step step : steps) {
      if (step.action() == null) {
        return "declares an empty sequence step";
      }
      if (step.atTicks() != null && (step.atTicks() < 0 || step.atTicks() > DelayActionData.MAX_TICKS)) {
        return "declares an atTicks cue outside 0.." + DelayActionData.MAX_TICKS;
      }
    }
    if (once != null && !StateSchema.KEY.matcher(once).matches()) {
      return "declares a once key that is not a state key";
    }
    return null;
  }

  /** One step: the action plus its optional cue, flattened in the document as {@code atTicks} beside the action fields. */
  @JsonAdapter(Step.Adapter.class)
  public record Step(Integer atTicks, MenuActionData action) {
    public static final class Adapter implements TypeAdapterFactory {
      @Override
      @SuppressWarnings("unchecked")
      public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() != Step.class) {
          return null;
        }
        TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
        TypeAdapter<MenuActionData> actions = gson.getAdapter(MenuActionData.class);
        return (TypeAdapter<T>) new TypeAdapter<Step>() {
          @Override
          public void write(JsonWriter out, Step value) throws IOException {
            if (value == null) {
              out.nullValue();
              return;
            }
            JsonElement tree = actions.toJsonTree(value.action());
            if (value.atTicks() != null && tree.isJsonObject()) {
              tree.getAsJsonObject().addProperty("atTicks", value.atTicks());
            }
            elements.write(out, tree);
          }

          @Override
          public Step read(JsonReader in) throws IOException {
            JsonElement tree = elements.read(in);
            if (!tree.isJsonObject()) {
              throw new IllegalArgumentException("a sequence step must be an action object");
            }
            JsonObject object = tree.getAsJsonObject();
            JsonElement cue = object.remove("atTicks");
            Integer atTicks = cue == null || cue.isJsonNull() ? null : cue.getAsInt();
            return new Step(atTicks, actions.fromJsonTree(object));
          }
        }.nullSafe();
      }
    }
  }
}
