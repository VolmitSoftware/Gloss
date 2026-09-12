package art.arcane.gloss.motion;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;

import java.util.List;

public interface TransformFrameSource {
    List<PacketWrapper<?>> compose(long nowMs);

    List<Player> viewers();

    boolean live();

    int fps();
}
