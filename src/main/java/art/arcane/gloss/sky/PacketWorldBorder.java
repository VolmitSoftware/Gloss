package art.arcane.gloss.sky;

import art.arcane.gloss.util.common.PacketUtils;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerInitializeWorldBorder;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The Spigot path: a per-viewer border sent as packets. Restoring re-sends the world's own border,
 * because the client has no way back to it on its own.
 */
public final class PacketWorldBorder implements BorderApplier {
    private static final long INSTANT = 0L;
    private static final int PORTAL_BOUNDARY = 29999984;
    private static final int WARNING_TIME_SECONDS = 15;

    @Override
    public void apply(Player viewer, SkyOverride.Border border) {
        send(viewer, border.centerX(), border.centerZ(), border.size(), border.warningBlocks());
    }

    @Override
    public void restore(Player viewer) {
        World world = viewer.getWorld();
        WorldBorder real = world == null ? null : world.getWorldBorder();
        if (real == null) {
            return;
        }
        send(viewer, real.getCenter().getX(), real.getCenter().getZ(), real.getSize(),
            real.getWarningDistance());
    }

    private static void send(Player viewer, double centerX, double centerZ, double size, int warningBlocks) {
        PacketWrapper<?> packet = new WrapperPlayServerInitializeWorldBorder(centerX, centerZ,
            size, size, INSTANT, PORTAL_BOUNDARY, WARNING_TIME_SECONDS, warningBlocks);
        PacketUtils.send(viewer, List.of(packet));
    }
}
