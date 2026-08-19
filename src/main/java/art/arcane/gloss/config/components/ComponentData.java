package art.arcane.gloss.config.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.volmlib.util.json.EnumType;
import com.google.gson.annotations.JsonAdapter;

@JsonAdapter(ComponentData.Adapter.class)
public interface ComponentData extends EnumType.Object<ComponentData> {

  MenuComponentType getType();

  MenuComponent<?> createComponent(MenuSession session, MenuComponentData data);

  class Adapter extends EnumType<ComponentData, MenuComponentType> {
    public Adapter() {
      super(ComponentData.class, MenuComponentType.class);
    }
  }
}
