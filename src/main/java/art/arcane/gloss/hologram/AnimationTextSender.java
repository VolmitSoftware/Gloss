package art.arcane.gloss.hologram;

import org.bukkit.entity.Player;

import java.util.List;

public interface AnimationTextSender {
    void send(List<Player> viewers, int entityId, String legacyText);
}
