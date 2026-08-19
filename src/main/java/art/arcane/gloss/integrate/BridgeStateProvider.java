package art.arcane.gloss.integrate;

import art.arcane.gloss.api.PreviewStateProvider;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;

final class BridgeStateProvider implements PreviewStateProvider {
    private final IntegrationBridge bridge;
    private final String namespace;

    BridgeStateProvider(IntegrationBridge bridge, String namespace) {
        this.bridge = bridge;
        this.namespace = namespace;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public Map<String, Object> snapshot(Block block, Entity entity, Player player) {
        return bridge.previewValues(namespace, System.currentTimeMillis());
    }
}
