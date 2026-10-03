package art.arcane.gloss.chat;

import org.bukkit.entity.Player;

import java.util.List;

public record ChatDispatch(ChannelRuntime channel, String message, List<Player> viewers, ChatContext context) {
}
