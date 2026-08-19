package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.ConnectActionData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ConnectMenuAction extends MenuAction<ConnectActionData> {
  static final String CHANNEL = "BungeeCord";

  public ConnectMenuAction(ConnectActionData data) {
    super(data);
  }

  public boolean hasValidServer() {
    return data.hasValidServer();
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    context.player().sendPluginMessage(Gloss.instance, CHANNEL, payload(data.server()));
    return ActionOutcome.CONTINUE;
  }

  static byte[] payload(String server) {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         DataOutputStream output = new DataOutputStream(bytes)) {
      output.writeUTF("Connect");
      output.writeUTF(server);
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Could not encode the BungeeCord connect payload", exception);
    }
  }
}
