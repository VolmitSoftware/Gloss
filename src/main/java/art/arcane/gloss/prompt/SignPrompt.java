package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A sign editor the player never walks up to. A fake oak sign is drawn three blocks under their
 * feet, the editor is opened on it, and the real block is sent back the moment the editor closes,
 * so nothing in the world is touched and no other player sees anything.
 */
public final class SignPrompt {
    /** Far enough below the feet that the fake sign is never in the player's own view. */
    public static final int EDITOR_Y_OFFSET = -3;

    private static final String SIGN_STATE = "minecraft:oak_sign";

    private final Gloss plugin;
    private final PromptService service;
    private final ConcurrentMap<UUID, Editor> editors = new ConcurrentHashMap<>();
    private PacketListenerCommon listener;

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
    }

    void disable() {
        if (listener != null && PacketEvents.getAPI() != null
            && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        listener = null;
        editors.clear();
    }

    boolean open(Player viewer, PromptRequest request) {
        Location anchor = viewer.getLocation();
        World world = anchor.getWorld();
        if (world == null) {
            return false;
        }
        Location block = anchor.clone().add(0, EDITOR_Y_OFFSET, 0);
        Vector3i position = new Vector3i(block.getBlockX(), block.getBlockY(), block.getBlockZ());
        WrappedBlockState state = WrappedBlockState.getByString(SIGN_STATE);
        if (state == null) {
            return false;
        }
        editors.put(viewer.getUniqueId(), new Editor(position, block));
        PacketUtils.send(viewer, new WrapperPlayServerBlockChange(position, state));
        PacketUtils.send(viewer, new WrapperPlayServerOpenSignEditor(position, true));
        scheduleTimeout(viewer, request);
        return true;
    }

    private void onUpdateSign(Player viewer, WrapperPlayClientUpdateSign packet) {
        Editor editor = editors.get(viewer.getUniqueId());
        PromptRequest request = service.pending(viewer.getUniqueId());
        if (editor == null || request == null || !editor.position().equals(packet.getBlockPosition())) {
            return;
        }
        editors.remove(viewer.getUniqueId(), editor);
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
        Location block = editor.block();
        FoliaScheduler.runEntity(plugin, viewer,
            () -> viewer.sendBlockChange(block, block.getBlock().getBlockData()));
    }

    private void scheduleTimeout(Player viewer, PromptRequest request) {
        FoliaScheduler.runEntity(plugin, viewer, () -> {
            Editor editor = editors.remove(viewer.getUniqueId());
            if (editor != null) {
                restore(viewer, editor);
            }
            service.timeout(viewer, request);
        }, request.timeoutTicks());
    }

    /** One viewer's open editor: where the fake sign is, in both packet and Bukkit terms. */
    public record Editor(Vector3i position, Location block) {
    }
}
