package art.arcane.gloss.dialog;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.internal.ApiEvents;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.bedrock.BedrockForms;
import art.arcane.gloss.bedrock.BedrockService;
import art.arcane.gloss.bedrock.CumulusForms;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.inventory.InventoryMenuService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Owns the {@code dialogs/} documents and the one screen each player may have open. A response is
 * only honoured while its token is the pending one and inside the configured window, so a dialog
 * the client kept across a reload or a replacement cannot run an action list.
 */
public final class DialogService implements GlossService {
    public static final String NAME = DialogDoc.KIND;

    private static volatile DialogService active;

    /** Where an encoded dialog goes. The packet path in production, a recorder in tests. */
    public interface Sink {
        void show(Player viewer, String documentId, String token, Dialog dialog);

        void clear(Player viewer);
    }

    /** Whether this viewer's client renders the dialog protocol at all. */
    @FunctionalInterface
    public interface ClientSupport {
        boolean supportsDialogs(Player viewer);
    }

    /** How a button's action list reaches the viewer's region thread. */
    @FunctionalInterface
    public interface Dispatcher {
        void run(Player viewer, Runnable task);
    }

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<DialogDoc> registry;
    private final Sink sink;
    private final ClientSupport support;
    private final Dispatcher dispatcher;
    private final LongSupplier clock;
    private final ConcurrentMap<UUID, PendingDialog> pending = new ConcurrentHashMap<>();
    private volatile Map<String, DialogRuntime> runtimes = Map.of();
    private DialogResponseListener listener;
    private volatile BedrockForms forms;

    public DialogService(Gloss plugin) {
        this(plugin, new PacketSink(), new ProtocolSupport(), new RegionDispatcher(plugin), System::currentTimeMillis);
    }

    DialogService(Gloss plugin, Sink sink, ClientSupport support, Dispatcher dispatcher, LongSupplier clock) {
        this.plugin = plugin;
        this.sink = sink;
        this.support = support;
        this.dispatcher = dispatcher;
        this.clock = clock;
        File folder = new File(plugin.getDataFolder(), DialogDoc.KIND);
        this.defaults = new ShippedDefaults(DialogDoc.KIND, folder, ShippedDocumentCatalog.DIALOGS.names());
        this.registry = DocumentRegistry.folder(DialogDoc.KIND, folder, DialogDoc::parse, DialogDoc::revision);
        active = this;
    }

    /** Null before the service is constructed; the actions reach the service through this. */
    public static DialogService active() {
        return active;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        ExprVariableNamespaces.global().register(new InputNamespace());
        ExprVariableNamespaces.global().register(new DialogNamespace());
    }

    @Override
    public void enable() {
        reload();
        plugin.watchdog().register(DialogDoc.KIND, this::poll);
        listener = DialogResponseListener.install(this);
    }

    @Override
    public void disable() {
        plugin.watchdog().unregister(DialogDoc.KIND);
        if (listener != null) {
            listener.uninstall();
            listener = null;
        }
        pending.clear();
        registry.close();
        runtimes = Map.of();
        if (active == this) {
            active = null;
        }
    }

    @Override
    public void reload() {
        defaults.extractMissing();
        registry.reload();
        rebuild();
    }

    public List<String> resetToDefault(String name) {
        List<String> restored = defaults.resetToDefault(name);
        if (!restored.isEmpty()) {
            registry.reload();
            rebuild();
        }
        return restored;
    }

    private void poll() {
        if (registry.poll() != null) {
            rebuild();
        }
        sweepExpired();
    }

    private void rebuild() {
        Map<String, DialogRuntime> built = new LinkedHashMap<>();
        for (Map.Entry<String, GlossDocument<DialogDoc>> entry : registry.snapshot().entrySet()) {
            try {
                built.put(entry.getKey(), DialogRuntime.compile(entry.getKey(), entry.getValue().value()));
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure, "Dialog %s failed to compile.", entry.getKey());
            }
        }
        runtimes = Map.copyOf(built);
    }

    public List<String> ids() {
        return List.copyOf(runtimes.keySet());
    }

    public DialogRuntime runtime(String id) {
        return runtimes.get(id);
    }

    public boolean enabled() {
        return GlossConfig.current().modules().dialogs().enabled();
    }

    /**
     * Opens a document for a viewer.
     *
     * @return true when the viewer now has this dialog on screen; false when the document is
     *     unknown, or when the client could not render it and the fallback took over or nothing did
     */
    public boolean open(Player viewer, String id, Map<String, Object> args) {
        DialogRuntime runtime = runtimes.get(id);
        if (viewer == null || runtime == null) {
            return false;
        }
        return show(viewer, runtime, args);
    }

    /** Opens a document that has no file behind it, built by the {@code confirm} action. */
    public boolean openAdHoc(Player viewer, String id, DialogDoc doc, Map<String, Object> args) {
        if (viewer == null || doc == null) {
            return false;
        }
        return show(viewer, DialogRuntime.compile(id, doc), args);
    }

    private boolean show(Player viewer, DialogRuntime runtime, Map<String, Object> args) {
        if (!support.supportsDialogs(viewer)) {
            return openBedrockForm(viewer, runtime, args) || openFallback(viewer, runtime, args);
        }
        DialogRuntime.Rendered rendered = runtime.render(viewer, args);
        String token = newToken();
        pending.put(viewer.getUniqueId(), new PendingDialog(viewer.getUniqueId(), runtime.id(), token,
            clock.getAsLong(), rendered.args(), rendered));
        sink.show(viewer, runtime.id(), token, DialogEncoder.encode(rendered, token));
        return true;
    }

    /**
     * A Bedrock viewer gets the same dialog as a Geyser form when Geyser is installed. The form is
     * answered into the same button index and input keys the packet path uses, so the document's
     * actions do not know which screen ran them.
     */
    private boolean openBedrockForm(Player viewer, DialogRuntime runtime, Map<String, Object> args) {
        if (!BedrockService.isBedrockPlayer(viewer.getUniqueId())) {
            return false;
        }
        BedrockForms forms = bedrockForms();
        if (forms == null) {
            return false;
        }
        DialogRuntime.Rendered rendered = runtime.render(viewer, args);
        return forms.send(viewer, rendered,
            answers -> onFormAnswer(viewer, runtime.id(), rendered, answers),
            () -> forget(viewer.getUniqueId()));
    }

    private BedrockForms bedrockForms() {
        BedrockForms current = forms;
        if (current == null) {
            current = new CumulusForms();
            forms = current;
        }
        return current instanceof CumulusForms cumulus && !cumulus.available() ? null : current;
    }

    /** Runs a Bedrock form answer through the same button and input path a packet response takes. */
    private void onFormAnswer(Player viewer, String documentId, DialogRuntime.Rendered rendered,
                              Map<String, Object> answers) {
        Object chosen = answers.get(BedrockForms.BUTTON);
        int index = chosen instanceof Number number ? number.intValue() : 0;
        DialogRuntime.Rendered.Button button = rendered.button(index);
        if (button == null) {
            return;
        }
        Map<String, Object> inputs = new HashMap<>(answers);
        inputs.remove(BedrockForms.BUTTON);
        PendingDialog open = new PendingDialog(viewer.getUniqueId(), documentId, newToken(),
            clock.getAsLong(), rendered.args(), rendered);
        dispatcher.run(viewer, () -> runButton(viewer, open, button, Map.copyOf(inputs)));
    }

    /**
     * The documented degradation for a client that cannot draw a dialog: the inventory named in
     * {@code fallback} first because a chest renders everywhere, then the menu. Bedrock viewers take
     * this path too until a forms bridge claims them.
     */
    private boolean openFallback(Player viewer, DialogRuntime runtime, Map<String, Object> args) {
        DialogFallback fallback = DialogFallback.choose(runtime.doc().fallback());
        boolean opened = switch (fallback.target()) {
            case INVENTORY -> openInventoryFallback(viewer, fallback.id(), args);
            case MENU -> openMenuFallback(viewer, fallback.id());
            case NONE -> false;
        };
        if (opened) {
            return true;
        }
        Gloss.logThrottled(Level.FINE, "dialog-unsupported-" + runtime.id(),
            "Dialog %s could not be shown to %s and its fallback did not take over.",
            runtime.id(), viewer.getName());
        tell(viewer, GlossMessages.FORMS_DIALOG_UNSUPPORTED);
        return false;
    }

    private boolean openInventoryFallback(Player viewer, String id, Map<String, Object> args) {
        InventoryMenuService inventories = InventoryMenuService.active();
        return inventories != null && inventories.open(viewer, id, args);
    }

    private boolean openMenuFallback(Player viewer, String id) {
        Gloss instance = Gloss.instance;
        if (instance == null || instance.getMenuCatalog() == null || instance.getSessionManager() == null) {
            return false;
        }
        MenuDefinitionData definition = instance.getMenuCatalog().definition(id).orElse(null);
        if (definition == null) {
            return false;
        }
        return instance.getSessionManager().createNewSession(viewer, definition, null, Map.of());
    }

    /**
     * The viewer, not the caller. A surface that cannot be drawn has to say so: the alternative is
     * a click that does nothing and a FINE log line no operator has enabled.
     */
    private void tell(Player viewer, TextKey key) {
        if (plugin != null && plugin.getLocalization() != null) {
            plugin.getLocalization().send(viewer, key);
        }
    }

    /** Clears whatever dialog this viewer has open. */
    public void close(Player viewer) {
        if (viewer == null) {
            return;
        }
        if (pending.remove(viewer.getUniqueId()) != null) {
            sink.clear(viewer);
        }
    }

    public void forget(UUID viewer) {
        pending.remove(viewer);
    }

    /**
     * Handles one {@code gloss:dialog} response payload. The press is claimed here, on the thread
     * the packet arrived on, so a client that resends the payload it was given finds the token
     * already spent rather than running the button's action list a second time.
     *
     * @return true when the payload claimed the pending dialog and its button's actions were queued
     */
    public boolean onResponse(Player viewer, NBTCompound payload) {
        if (viewer == null || payload == null) {
            return false;
        }
        PendingDialog open = pending.get(viewer.getUniqueId());
        if (open == null) {
            return false;
        }
        String documentId = string(payload, DialogEncoder.PAYLOAD_DOCUMENT);
        String token = string(payload, DialogEncoder.PAYLOAD_TOKEN);
        if (!open.docId().equals(documentId) || !open.token().equals(token)) {
            return false;
        }
        if (expired(open)) {
            pending.remove(viewer.getUniqueId(), open);
            return false;
        }
        NBTNumber index = payload.getNumberTagOrNull(DialogEncoder.PAYLOAD_BUTTON);
        DialogRuntime.Rendered.Button button = index == null ? null : open.rendered().button(index.getAsInt());
        if (button == null) {
            return false;
        }
        if (!open.claim()) {
            return false;
        }
        Map<String, Object> inputs = bindInputs(open.rendered(), payload);
        dispatcher.run(viewer, () -> runButton(viewer, open, button, inputs));
        return true;
    }

    private void runButton(Player viewer, PendingDialog open, DialogRuntime.Rendered.Button button,
                           Map<String, Object> inputs) {
        DialogActionContext context = new DialogActionContext(viewer, open.docId(), button.index(), open.args());
        ActionOutcome[] outcome = new ActionOutcome[1];
        InputNamespace.bind(open.docId(), inputs, () -> outcome[0] = MenuAction.execute(button.actions(), context));
        ApiEvents.fireDialogSubmit(viewer, open.docId(), button.index());
        boolean tookOver = outcome[0] == ActionOutcome.STOP;
        if (pending.get(viewer.getUniqueId()) != open) {
            return;
        }
        switch (open.rendered().afterAction()) {
            case DialogDoc.AFTER_WAIT -> {
                if (!tookOver) {
                    reshow(viewer, open);
                }
            }
            case DialogDoc.AFTER_CLOSE -> {
                if (!tookOver) {
                    close(viewer);
                }
            }
            default -> open.rearm();
        }
    }

    /** Puts the same screen back up under a fresh token, for {@code afterAction: wait_for_response}. */
    private void reshow(Player viewer, PendingDialog open) {
        String token = newToken();
        pending.put(viewer.getUniqueId(), new PendingDialog(viewer.getUniqueId(), open.docId(), token,
            clock.getAsLong(), open.args(), open.rendered()));
        sink.show(viewer, open.docId(), token, DialogEncoder.encode(open.rendered(), token));
    }

    /** The answers the client attached, typed by what the document declared each key to be. */
    Map<String, Object> bindInputs(DialogRuntime.Rendered rendered, NBTCompound payload) {
        Map<String, Object> bound = new HashMap<>(rendered.inputs().size());
        for (DialogRuntime.Rendered.Input input : rendered.inputs()) {
            DialogInput source = input.source();
            NBT tag = payload.getTagOrNull(source.key());
            bound.put(source.key(), value(source, tag));
        }
        return Map.copyOf(bound);
    }

    private static Object value(DialogInput source, NBT tag) {
        return switch (source.type()) {
            case DialogInput.NUMBER -> tag instanceof NBTNumber number
                ? Double.valueOf(number.getAsDouble())
                : Double.valueOf(source.initialNumber() == null ? 0D : source.initialNumber());
            case DialogInput.BOOL -> tag instanceof NBTNumber number
                ? Boolean.valueOf(number.getAsInt() != 0)
                : Boolean.valueOf(source.initialBoolean());
            default -> tag instanceof NBTString text ? text.getValue() : "";
        };
    }

    private void sweepExpired() {
        pending.values().removeIf(this::expired);
    }

    private boolean expired(PendingDialog open) {
        long window = 1000L * GlossConfig.current().modules().dialogs().responseTimeoutSeconds();
        return clock.getAsLong() - open.openedAtMs() > window;
    }

    private static String string(NBTCompound payload, String key) {
        NBT tag = payload.getTagOrNull(key);
        return tag instanceof NBTString text ? text.getValue() : null;
    }

    private static String newToken() {
        return Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
    }

    /** The production path: one show or clear packet to the viewer. */
    private static final class PacketSink implements Sink {
        @Override
        public void show(Player viewer, String documentId, String token, Dialog dialog) {
            PacketUtils.send(viewer, new WrapperPlayServerShowDialog(dialog));
        }

        @Override
        public void clear(Player viewer) {
            PacketUtils.send(viewer, new WrapperPlayServerClearDialog());
        }
    }

    /**
     * Dialogs landed in 1.21.6. An older client renders nothing at all, and a Bedrock viewer gets
     * only a partial translation with empty text inputs, so both take the fallback.
     */
    private static final class ProtocolSupport implements ClientSupport {
        @Override
        public boolean supportsDialogs(Player viewer) {
            if (BedrockService.isBedrockPlayer(viewer.getUniqueId())) {
                return false;
            }
            if (PacketEvents.getAPI() == null || PacketEvents.getAPI().getPlayerManager() == null) {
                return false;
            }
            ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(viewer);
            return version != null && !version.isOlderThan(ClientVersion.V_1_21_6);
        }
    }

    private record RegionDispatcher(Gloss plugin) implements Dispatcher {
        @Override
        public void run(Player viewer, Runnable task) {
            FoliaScheduler.runEntity(plugin, viewer, task);
        }
    }
}
