package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A sign editor the player never walks up to. A fake oak sign is drawn three blocks under their
 * feet, the editor is opened on it, and the real block is sent back the moment the editor closes,
 * so nothing in the world is touched and no other player sees anything.
 */
public final class SignPrompt {
    /** Far enough below the feet that the fake sign is never in the player's own view. */
    public static final int EDITOR_Y_OFFSET = -3;

    private final Gloss plugin;
    private final PromptService service;
    private final ConcurrentMap<UUID, Editor> editors = new ConcurrentHashMap<>();
    private PacketListenerCommon listener;
    private PacketListenerCommon resetListener;

    SignPrompt(Gloss plugin, PromptService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void enable() {
        if (PacketEvents.getAPI() == null || PacketEvents.getAPI().getEventManager() == null) {
            return;
        }
        listener = PacketEvents.getAPI().getEventManager().registerListener(
            new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
                @Override
                public void onPacketReceive(PacketReceiveEvent event) {
                    if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN
                        && event.getPlayer() instanceof Player viewer) {
                        onUpdateSign(viewer, new WrapperPlayClientUpdateSign(event));
                    }
                }
            });
        resetListener = PacketEvents.getAPI().getEventManager().registerListener(
            new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
                @Override
                public void onPacketSend(PacketSendEvent event) {
                    if (!event.isCancelled() && event.getPacketType() == PacketType.Play.Server.RESPAWN) {
                        worldReset(event.getUser().getUUID());
                    }
                }
            });
    }

    void disable() {
        if (listener != null && PacketEvents.getAPI() != null
            && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        if (resetListener != null && PacketEvents.getAPI() != null
            && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(resetListener);
        }
        listener = null;
        resetListener = null;
        for (UUID viewer : editors.keySet()) {
            release(viewer);
        }
    }

    boolean open(Player viewer, PromptRequest request) {
        Location anchor = viewer.getLocation();
        World world = anchor.getWorld();
        if (world == null) {
            return false;
        }
        Location block = anchor.clone().add(0, EDITOR_Y_OFFSET, 0);
        block.setY(Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, block.getBlockY())));
        Vector3i position = new Vector3i(block.getBlockX(), block.getBlockY(), block.getBlockZ());
        WrappedBlockState state = signState(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());
        Editor editor = new Editor(viewer, position, block, block.getBlock().getBlockData().clone(), request);
        editors.put(viewer.getUniqueId(), editor);
        PacketUtils.send(viewer, new WrapperPlayServerBlockChange(position, state));
        viewer.sendSignChange(block, initialLines(request.initial()));
        PacketUtils.send(viewer, new WrapperPlayServerOpenSignEditor(position, true));
        scheduleTimeout(viewer, request, editor);
        return true;
    }

    static String[] initialLines(String initial) {
        String[] lines = new String[]{"", "", "", ""};
        String[] source = initial.split("\\R", -1);
        System.arraycopy(source, 0, lines, 0, Math.min(source.length, lines.length));
        return lines;
    }

    static WrappedBlockState signState(ClientVersion version) {
        return WrappedBlockState.getDefaultState(version, StateTypes.OAK_SIGN);
    }

    private void onUpdateSign(Player viewer, WrapperPlayClientUpdateSign packet) {
        Editor editor = editors.get(viewer.getUniqueId());
        PromptRequest request = service.pending(viewer.getUniqueId());
        if (editor == null || request != editor.request() || !editor.position().equals(packet.getBlockPosition())) {
            return;
        }
        if (!retire(viewer.getUniqueId(), editor)) {
            return;
        }
        String answer = join(packet.getTextLines());
        restore(viewer, editor);
        FoliaScheduler.runEntity(plugin, viewer, () -> service.complete(viewer, request, answer));
    }

    /** Joins the four sign lines into one answer, collapsing the blanks the client always sends. */
    public static String join(String[] lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(line.trim());
        }
        return joined.toString();
    }

    private void restore(Player viewer, Editor editor) {
        if (!viewer.isOnline()) {
            return;
        }
        FoliaScheduler.runEntity(plugin, viewer,
            () -> restoreSnapshot(editor));
    }

    void release(UUID viewerId) {
        Editor editor = editors.remove(viewerId);
        if (editor != null) {
            restore(editor.viewer(), editor);
        }
    }

    void release(UUID viewerId, PromptRequest request) {
        Editor editor = editors.get(viewerId);
        if (editor != null && editor.request() == request && retire(viewerId, editor)) {
            restore(editor.viewer(), editor);
        }
    }

    void worldReset(UUID viewerId) {
        resetEditor(viewerId, editors.get(viewerId));
    }

    void resetEditor(UUID viewerId, Editor editor) {
        if (editor != null && retire(viewerId, editor)) {
            service.cancel(viewerId, editor.request());
        }
    }

    private boolean retire(UUID viewerId, Editor editor) {
        AtomicBoolean removed = new AtomicBoolean();
        editors.computeIfPresent(viewerId, (id, current) -> {
            if (current != editor) {
                return current;
            }
            removed.set(true);
            return null;
        });
        return removed.get();
    }

    static void restoreSnapshot(Editor editor) {
        Player viewer = editor.viewer();
        if (viewer.isOnline() && viewer.getWorld() == editor.block().getWorld()) {
            viewer.sendBlockChange(editor.block(), editor.original());
        }
    }

    private void scheduleTimeout(Player viewer, PromptRequest request, Editor opened) {
        FoliaScheduler.runEntity(plugin, viewer, () -> expire(viewer, request, opened), request.timeoutTicks());
    }

    void expire(Player viewer, PromptRequest request, Editor opened) {
        if (retire(viewer.getUniqueId(), opened)) {
            restore(viewer, opened);
        }
        service.timeout(viewer, request);
    }

    /** One viewer's open editor: where the fake sign is, in both packet and Bukkit terms. */
    public record Editor(Player viewer, Vector3i position, Location block, BlockData original, PromptRequest request) {
    }
}
