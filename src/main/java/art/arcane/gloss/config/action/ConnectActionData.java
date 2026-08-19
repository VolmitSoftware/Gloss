package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;

import java.util.regex.Pattern;

public record ConnectActionData(String server, HoloClickTrigger trigger) implements MenuActionData {
  private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  @Override
  public MenuActionType getType() {
    return MenuActionType.CONNECT;
  }

  public boolean hasValidServer() {
    return server != null && SERVER_NAME.matcher(server).matches();
  }
}
