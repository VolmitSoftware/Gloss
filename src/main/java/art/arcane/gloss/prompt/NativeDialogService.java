package art.arcane.gloss.prompt;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.dialog.ConfirmationDialog;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class NativeDialogService {
    private static final String PREFIX = "gloss:dialog/";

    private final Runtime runtime;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Pending> displayed = new HashMap<>();
    private PacketListenerCommon listener;
    private volatile boolean running;
    private volatile long generation;
    private long nextExpiry = Long.MAX_VALUE;

    NativeDialogService(Gloss plugin) {
        this(new PacketRuntime(plugin));
    }

    NativeDialogService(Runtime runtime) {
        this.runtime = runtime;
    }

    synchronized void enable() {
        running = true;
        generation++;
        runtime.startSweep(this::sweep);
        if (PacketEvents.getAPI() == null || PacketEvents.getAPI().getEventManager() == null) {
            return;
        }
        listener = PacketEvents.getAPI().getEventManager().registerListener(
            new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
                @Override
                public void onPacketReceive(PacketReceiveEvent event) {
                    receive(event);
                }

                @Override
                public void onPacketSend(PacketSendEvent event) {
                    sent(event);
                }
            });
    }

    synchronized void disable() {
        running = false;
        generation++;
        runtime.stopSweep();
        for (Pending entry : List.copyOf(pending.values())) {
            retire(entry, true);
        }
        if (listener != null && PacketEvents.getAPI() != null && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        listener = null;
    }

    public synchronized boolean open(ActionContext context, DialogDefinition definition) {
        Player viewer = context.player();
        if (!running || viewer == null || !context.current() || !runtime.supported(viewer)) {
            return false;
        }
        Pending entry = new Pending(context, definition, UUID.randomUUID().toString(), generation,
            runtime.nowMillis() + definition.timeoutTicks() * 50L);
        Dialog dialog = definition.create(context, entry.nonce);
        pending.put(viewer.getUniqueId(), entry);
        displayed.put(viewer.getUniqueId(), entry);
        nextExpiry = Math.min(nextExpiry, entry.expiresAt);
        try {
            runtime.show(viewer, dialog);
        } catch (RuntimeException failure) {
            retire(entry, true);
            Gloss.logExceptionStack(false, failure, "Native dialog could not open for %s.", viewer.getUniqueId());
            return false;
        }
        return true;
    }

    public synchronized boolean available(Player viewer) {
        return running && viewer != null && runtime.supported(viewer);
    }

    public synchronized void clear(Player viewer) {
        Pending entry = pending.get(viewer.getUniqueId());
        if (entry != null && entry.context.player() == viewer) {
            retire(entry, true);
        }
    }

    synchronized boolean answer(Player viewer, ResourceLocation id, NBT payload) {
        Pending entry = pending.get(viewer.getUniqueId());
        if (!running || entry == null || entry.context.player() != viewer || entry.responded
            || runtime.nowMillis() >= entry.expiresAt) {
            return false;
        }
        String expected = PREFIX + entry.nonce + "/";
        String identifier = id.toString();
        if (!identifier.startsWith(expected)) {
            return false;
        }
        int index;
        Map<String, Object> values;
        try {
            index = Integer.parseInt(identifier.substring(expected.length()));
            if (index < 0 || index >= entry.definition.buttonCount()) {
                return false;
            }
            values = entry.definition.validate(payload);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        entry.responded = true;
        int selected = index;
        if (!runtime.schedule(viewer, new Scheduled(() -> complete(entry, selected, values), 0,
            () -> retire(entry, false)))) {
            retire(entry, true);
            return false;
        }
        return true;
    }

    synchronized void cancel(Player viewer) {
        Pending entry = displayed.get(viewer.getUniqueId());
        if (entry != null && entry.context.player() == viewer) {
            retire(entry, false);
        }
    }

    synchronized void displayed(UUID viewer, Dialog dialog) {
        Pending entry = displayed.get(viewer);
        if (entry != null && !owned(dialog)) {
            retire(entry, false);
        }
    }

    private void complete(Pending entry, int button, Map<String, Object> values) {
        synchronized (this) {
            if (!current(entry)) {
                return;
            }
            retire(entry, false);
        }
        if (running && generation == entry.generation && entry.context.player().isOnline() && entry.context.current()) {
            MenuAction.execute(entry.definition.actions(button), new DialogActionContext(entry.context, values));
        }
    }

    private void timeout(Pending entry) {
        synchronized (this) {
            if (!current(entry) || entry.responded) {
                return;
            }
            retire(entry, true);
        }
        if (running && generation == entry.generation && entry.context.player().isOnline() && entry.context.current()) {
            MenuAction.execute(entry.definition.timeout(), entry.context);
        }
    }

    synchronized void sweep() {
        long now = runtime.nowMillis();
        if (!running || now < nextExpiry) {
            return;
        }
        nextExpiry = Long.MAX_VALUE;
        for (Pending entry : List.copyOf(pending.values())) {
            if (entry.responded || entry.timeoutScheduled) {
                continue;
            }
            if (entry.expiresAt > now) {
                nextExpiry = Math.min(nextExpiry, entry.expiresAt);
                continue;
            }
            entry.timeoutScheduled = true;
            if (!runtime.schedule(entry.context.player(), new Scheduled(() -> timeout(entry), 0,
                () -> retire(entry, false)))) {
                retire(entry, false);
            }
        }
    }

    private boolean current(Pending entry) {
        return running && generation == entry.generation
            && pending.get(entry.context.player().getUniqueId()) == entry;
    }

    private synchronized void retire(Pending entry, boolean clear) {
        Player viewer = entry.context.player();
        pending.remove(viewer.getUniqueId(), entry);
        if (!clear) {
            displayed.remove(viewer.getUniqueId(), entry);
        } else if (displayed.get(viewer.getUniqueId()) == entry) {
            runtime.clear(viewer, () -> claimClear(entry));
        }
    }

    private synchronized boolean claimClear(Pending entry) {
        return displayed.remove(entry.context.player().getUniqueId(), entry);
    }

    private void receive(PacketReceiveEvent event) {
        if (event.isCancelled() || event.getPacketType() != PacketType.Play.Client.CUSTOM_CLICK_ACTION
            || !(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        WrapperPlayClientCustomClickAction packet = new WrapperPlayClientCustomClickAction(event);
        if (packet.getId().toString().startsWith(PREFIX)) {
            event.setCancelled(true);
            answer(viewer, packet.getId(), packet.getPayload());
        }
    }

    private void sent(PacketSendEvent event) {
        if (event.isCancelled() || event.getUser() == null) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.SHOW_DIALOG) {
            displayed(event.getUser().getUUID(), new WrapperPlayServerShowDialog(event).getDialog());
        } else if (event.getPacketType() == PacketType.Play.Server.CLEAR_DIALOG
            || event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            displayed(event.getUser().getUUID(), null);
        }
    }

    private static boolean owned(Dialog dialog) {
        ActionButton button = switch (dialog) {
            case NoticeDialog notice -> notice.getAction();
            case ConfirmationDialog confirmation -> confirmation.getYesButton();
            case MultiActionDialog multiple -> multiple.getActions().isEmpty() ? null : multiple.getActions().getFirst();
            case null, default -> null;
        };
        return button != null && button.getAction() instanceof DynamicCustomAction custom
            && custom.getId().toString().startsWith(PREFIX);
    }

    interface Runtime {
        boolean supported(Player viewer);

        void show(Player viewer, Dialog dialog);

        void clear(Player viewer, BooleanSupplier owned);

        boolean schedule(Player viewer, Scheduled task);

        long nowMillis();

        void startSweep(Runnable sweep);

        void stopSweep();
    }

    record Scheduled(Runnable task, int delayTicks, Runnable retired) {
    }

    private static final class PacketRuntime implements Runtime {
        private final Gloss plugin;
        private int sweepTask = -1;

        private PacketRuntime(Gloss plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean supported(Player viewer) {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().getPlayerManager() != null
                && PacketEvents.getAPI().getServerManager().getVersion().toClientVersion()
                    .isNewerThanOrEquals(ClientVersion.V_1_21_6)
                && PacketEvents.getAPI().getPlayerManager().getClientVersion(viewer)
                    .isNewerThanOrEquals(ClientVersion.V_1_21_6);
        }

        @Override
        public void show(Player viewer, Dialog dialog) {
            PacketUtils.send(viewer, new WrapperPlayServerShowDialog(dialog));
        }

        @Override
        public void clear(Player viewer, BooleanSupplier owned) {
            Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(viewer);
            if (channel == null || !ChannelHelper.isOpen(channel)) {
                owned.getAsBoolean();
                return;
            }
            ChannelHelper.runInEventLoop(channel, () -> {
                if (owned.getAsBoolean()) {
                    PacketUtils.send(viewer, new WrapperPlayServerClearDialog());
                }
            });
        }

        @Override
        public boolean schedule(Player viewer, Scheduled task) {
            return FoliaScheduler.runEntity(plugin, viewer, task.task(), task.delayTicks(), task.retired());
        }

        @Override
        public long nowMillis() {
            return System.nanoTime() / 1_000_000L;
        }

        @Override
        public void startSweep(Runnable sweep) {
            if (plugin != null && plugin.scheduler() != null) {
                sweepTask = plugin.scheduler().sr(sweep, 1);
            }
        }

        @Override
        public void stopSweep() {
            if (sweepTask != -1) {
                plugin.scheduler().csr(sweepTask);
                sweepTask = -1;
            }
        }
    }

    private static final class Pending {
        private final ActionContext context;
        private final DialogDefinition definition;
        private final String nonce;
        private final long generation;
        private final long expiresAt;
        private boolean responded;
        private boolean timeoutScheduled;

        private Pending(ActionContext context, DialogDefinition definition, String nonce, long generation, long expiresAt) {
            this.context = context;
            this.definition = definition;
            this.nonce = nonce;
            this.generation = generation;
            this.expiresAt = expiresAt;
        }
    }
}
