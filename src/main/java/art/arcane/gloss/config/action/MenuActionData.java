package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.volmlib.util.json.EnumType;
import com.google.gson.annotations.JsonAdapter;

@JsonAdapter(MenuActionData.Adapter.class)
public interface MenuActionData extends EnumType.Object<MenuActionData> {
  MenuActionType getType();

  HoloClickTrigger trigger();

  default HoloClickTrigger triggerOrDefault() {
    return trigger() == null ? HoloClickTrigger.ANY : trigger();
  }

  class Adapter extends EnumType<MenuActionData, MenuActionType> {
    public Adapter() {
      super(MenuActionData.class, MenuActionType.class);
    }
  }
}
