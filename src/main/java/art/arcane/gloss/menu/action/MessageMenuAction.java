package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.MessageActionData;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class MessageMenuAction extends MenuAction<MessageActionData> {
  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
      .character(LegacyComponentSerializer.SECTION_CHAR)
      .hexColors()
      .useUnusualXRepeatedCharacterHexFormat()
      .build();

  public MessageMenuAction(MessageActionData data) {
    super(data);
  }

  public boolean hasMessage() {
    return data.message() != null && !data.message().isBlank();
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Player player = context.player();
    String personalized = data.message().replace("%player%", player.getName());
    String resolved = TextPipeline.menuText(player, personalized);
    deliver(player, sanitizeInteractions(TextUtils.parse(resolved)));
    return ActionOutcome.CONTINUE;
  }

  private static void deliver(Player player, Component message) {
    if (player instanceof Audience audience) {
      audience.sendMessage(message);
      return;
    }
    player.sendMessage(LEGACY.serialize(message));
  }

  private static Component sanitizeInteractions(Component component) {
    List<Component> children = new ArrayList<>(component.children().size());
    for (Component child : component.children()) {
      children.add(sanitizeInteractions(child));
    }
    return component.clickEvent(null).insertion(null).children(children);
  }
}
