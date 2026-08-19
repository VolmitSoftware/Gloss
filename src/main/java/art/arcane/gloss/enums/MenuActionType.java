package art.arcane.gloss.enums;

import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.config.action.ConnectActionData;
import art.arcane.gloss.config.action.MessageActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.NavigationActionData;
import art.arcane.gloss.config.action.SoundActionData;
import art.arcane.gloss.config.action.TeleportActionData;
import art.arcane.volmlib.util.json.EnumType;

public enum MenuActionType implements EnumType.Values<MenuActionData> {

  COMMAND("command", CommandActionData.class),
  SOUND("sound", SoundActionData.class),
  MESSAGE("message", MessageActionData.class),
  TELEPORT("teleport", TeleportActionData.class),
  CONNECT("connect", ConnectActionData.class),
  NAVIGATE("navigate", NavigationActionData.class);

  private final String value;
  private final Class<? extends MenuActionData> type;

  MenuActionType(String value, Class<? extends MenuActionData> type) {
    this.value = value;
    this.type = type;
  }

  public String getSerializedName() {
    return value;
  }

  @Override
  public Class<? extends MenuActionData> getType() {
    return type;
  }
}
