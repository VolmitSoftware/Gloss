package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.volmlib.util.json.EnumType;
import com.google.gson.annotations.JsonAdapter;

import java.util.List;

@JsonAdapter(MenuActionData.Adapter.class)
public interface MenuActionData extends EnumType.Object<MenuActionData> {
  MenuActionType getType();

  HoloClickTrigger trigger();

  default HoloClickTrigger triggerOrDefault() {
    return trigger() == null ? HoloClickTrigger.ANY : trigger();
  }

  /** The runtime action for this data; a new action type implements this and adds one enum constant. */
  MenuAction<?> createAction();

  /**
   * The {@code when} gate and {@code cooldownTicks} window this action carries. Every action record
   * declares {@code String when, Integer cooldownTicks} as its last two components and returns
   * {@code ActionEnvelope.of(when, cooldownTicks)} here; the runner in {@link MenuAction#execute}
   * enforces both.
   */
  default ActionEnvelope envelope() {
    return ActionEnvelope.NONE;
  }

  /**
   * Why this action can never do anything, phrased to follow "component X ..." in a warning, or
   * null when it is well-formed. Checked once at document load so a misconfigured action is
   * reported instead of silently skipped at click time.
   */
  default String invalidReason() {
    return null;
  }

  /**
   * Every action list this action carries inside it (control-flow branches, sequence steps), so a
   * document can scan its whole action tree at load. Leaf actions carry none.
   */
  default List<MenuActionData> nestedActions() {
    return List.of();
  }

  class Adapter extends EnumType<MenuActionData, MenuActionType> {
    public Adapter() {
      super(MenuActionData.class, MenuActionType.class);
    }
  }
}
