package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * The chat engine: {@code channels/} documents, the channel a player is talking in, and the one
 * dispatch path both listener flavours share. Nothing here touches Bukkit chat events; the Paper
 * bridge and the Spigot listener each drive {@link #dispatch} and act on the outcome.
 */
public final class ChannelService implements GlossService, Listener {
    public static final String NAME = "channels";
    public static final String PRIVATE_CHANNEL = "private";

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<ChannelDoc> registry;
    private final ChatState state = new ChatState();
    private final ChatThrottle throttle = new ChatThrottle();
    private final ChatMessageRenderer renderer;
    private volatile Index index = Index.EMPTY;
    private volatile boolean started;

    public ChannelService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), ChannelDoc.KIND);
        this.defaults = new ShippedDefaults(ChannelDoc.KIND, folder, ShippedDocumentCatalog.CHANNELS.names());
        this.registry = DocumentRegistry.folder(ChannelDoc.KIND, folder, ChannelDoc::parse, ChannelDoc::revision);
        this.renderer = new ChatMessageRenderer(plugin);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        if (!plugin.cfg().modules().channels().enabled()) {
            return;
        }
        started = true;
        defaults.extractMissing();
        registry.reload();
        rebuild(registry.snapshot());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerCommands();
        plugin.watchdog().register(ChannelDoc.KIND, this::poll);
    }

    @Override
    public void disable() {
        started = false;
        plugin.watchdog().unregister(ChannelDoc.KIND);
        HandlerList.unregisterAll(this);
        registry.close();
        index = Index.EMPTY;
        state.clear();
        throttle.clear();
    }

    @Override
    public void reload() {
        if (!started) {
            return;
        }
        defaults.extractMissing();
        registry.reload();
        rebuild(registry.snapshot());
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return previous.modules().channels().enabled() != next.modules().channels().enabled();
    }

    /** True when the engine is carrying chat, so the listeners know which path to take. */
    public boolean active() {
        return started && index.defaultChannel() != null;
    }

    public ChannelRuntime defaultChannel() {
        return index.defaultChannel();
    }

    public List<ChannelRuntime> channels() {
        return List.copyOf(index.byId().values());
    }

    /** Looks a channel up by id, name or alias, case-insensitively. */
    public ChannelRuntime channelFor(String nameOrAlias) {
        return nameOrAlias == null ? null : index.byName().get(nameOrAlias.toLowerCase(Locale.ROOT));
    }

    public ChannelRuntime activeChannel(Player sender) {
        String selected = state.channelOf(sender.getUniqueId());
        ChannelRuntime chosen = selected == null ? null : index.byId().get(selected);
        return chosen != null && mayUse(sender, chosen) ? chosen : index.defaultChannel();
    }

    public boolean selectChannel(Player player, String nameOrAlias) {
        ChannelRuntime channel = channelFor(nameOrAlias);
        if (channel == null || !mayUse(player, channel)) {
            return false;
        }
        state.setChannel(player.getUniqueId(), channel.id());
        return true;
    }

    public ChatState state() {
        return state;
    }

    /** The shipped direct channel {@code /msg} and {@code /r} render through. */
    public ChannelRuntime privateChannel() {
        ChannelRuntime direct = channelFor(PRIVATE_CHANNEL);
        return direct != null && direct.scope() == ChannelDoc.Scope.DIRECT ? direct : null;
    }

    /** What a whisper did; the caller turns anything but {@link PrivateResult#SENT} into a reply. */
    public enum PrivateResult {
        SENT,
        NO_CHANNEL,
        DENIED,
        FILTERED,
        TOO_FAST,
        REPEAT
    }

    /**
     * Renders a private message for both sides and records the pair so either may answer with
     * {@code /r}. The direct channel's own permission and throttle apply: anti-spam a whisper
     * walks around is the one a spammer uses.
     */
    public PrivateResult sendPrivate(Player sender, Player target, String rawMessage) {
        ChannelRuntime channel = privateChannel();
        if (channel == null) {
            return PrivateResult.NO_CHANNEL;
        }
        if (!mayUse(sender, channel)) {
            return PrivateResult.DENIED;
        }
        String filtered = ChatFilters.apply(channel, rawMessage);
        if (filtered == null) {
            return PrivateResult.FILTERED;
        }
        ChatThrottle.Verdict verdict = throttle.check(sender.getUniqueId(), normalize(filtered),
            System.currentTimeMillis(), channel.doc().throttle());
        if (verdict == ChatThrottle.Verdict.TOO_FAST) {
            return PrivateResult.TOO_FAST;
        }
        if (verdict == ChatThrottle.Verdict.REPEAT) {
            return PrivateResult.REPEAT;
        }
        deliver(channel, sender, target, filtered);
        deliver(channel, sender, sender, filtered);
        state.pairConversation(sender.getUniqueId(), target.getUniqueId());
        return PrivateResult.SENT;
    }

    public boolean mayUse(Player sender, ChannelRuntime channel) {
        String permission = channel.doc().channel().permission();
        if (!permission.isEmpty() && !sender.hasPermission(permission)) {
            return false;
        }
        return channel.doc().show().matches(plugin, sender);
    }

    /**
     * Filters, throttles, lets a waiting prompt take the line, fans the hooks out once and hands
     * the audience to the caller.
     *
     * @return true when the message reached an audience
     */
    public boolean dispatch(Player sender, String rawMessage, ChatSink sink) {
        if (ChatCapture.consume(sender.getUniqueId(), rawMessage)) {
            sink.dropped(ChatDrop.CAPTURED);
            return false;
        }
        ChannelRuntime channel = activeChannel(sender);
        if (channel == null) {
            sink.dropped(ChatDrop.NO_CHANNEL);
            return false;
        }
        String filtered = ChatFilters.apply(channel, rawMessage);
        if (filtered == null) {
            return refuse(sender, sink, ChatDrop.FILTERED, GlossMessages.CHAT_FILTERED);
        }
        ChatThrottle.Verdict verdict = throttle.check(sender.getUniqueId(), normalize(filtered),
            System.currentTimeMillis(), channel.doc().throttle());
        if (verdict == ChatThrottle.Verdict.TOO_FAST) {
            return refuse(sender, sink, ChatDrop.TOO_FAST, GlossMessages.CHAT_TOO_FAST);
        }
        if (verdict == ChatThrottle.Verdict.REPEAT) {
            return refuse(sender, sink, ChatDrop.REPEAT, GlossMessages.CHAT_REPEAT);
        }
        plugin.chat().dispatchHooks(sender, filtered);
        sink.audience(channel, filtered, ChatAudience.viewers(channel, sender,
            plugin.getServer().getOnlinePlayers()));
        return true;
    }

    public ChatMessageRenderer.Rendered render(ChannelRuntime channel, Player sender, Player viewer,
                                               String message, ChatContext context) {
        return renderer.render(channel, sender, viewer, message,
            context.withItem(state.heldItem(sender.getUniqueId())));
    }

    /**
     * The Spigot fallback renders once, as the sender, because {@code AsyncPlayerChatEvent} carries
     * one format for the whole audience. {@code %} is doubled: Bukkit runs the format through
     * {@link String#format}.
     */
    public String spigotFormat(ChannelRuntime channel, Player sender, String message) {
        String legacy = TextUtils.renderLegacy(
            render(channel, sender, sender, message, ChatContext.PLAIN).miniMessage());
        return legacy.replace("%", "%%");
    }

    /** Plays the channel's mention cue on the mentioned viewer's own region thread. */
    public void playMentionCue(ChannelRuntime channel, Player viewer) {
        String sound = channel.doc().mentions().sound();
        if (sound.isEmpty()) {
            return;
        }
        SchedulerUtils.runEntity(plugin, viewer,
            () -> viewer.playSound(viewer.getLocation(), sound, 1.0F, 1.0F));
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        state.setHeldItem(player.getUniqueId(),
            itemToken(player.getInventory().getItem(event.getNewSlot())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        state.forget(event.getPlayer().getUniqueId());
        throttle.forget(event.getPlayer().getUniqueId());
        ChatCapture.release(event.getPlayer().getUniqueId());
    }

    public List<String> resetToDefault(String nameOrStar) {
        List<String> restored = defaults.resetToDefault(nameOrStar);
        if (!restored.isEmpty()) {
            registry.reload();
            rebuild(registry.snapshot());
        }
        return restored;
    }

    private boolean refuse(Player sender, ChatSink sink, ChatDrop reason,
                           art.arcane.volmlib.util.localization.TextKey message) {
        GlossLocalization.sendGlobal(sender, message);
        sink.dropped(reason);
        return false;
    }

    private void deliver(ChannelRuntime channel, Player sender, Player viewer, String message) {
        ChatMessageRenderer.Rendered rendered = render(channel, sender, viewer, message, ChatContext.PLAIN);
        ComponentMessenger.sendMarkup(viewer, rendered.miniMessage());
        if (rendered.mentioned()) {
            playMentionCue(channel, viewer);
        }
    }

    private static String normalize(String message) {
        return message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    @SuppressWarnings("deprecation")
    private static ChatBody.Item itemToken(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName()
            : prettyName(stack.getType());
        return new ChatBody.Item(stack.getType().getKey().toString(), name, stack.getAmount());
    }

    private static String prettyName(Material material) {
        String[] words = material.getKey().getKey().split("_");
        StringBuilder pretty = new StringBuilder(material.getKey().getKey().length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!pretty.isEmpty()) {
                pretty.append(' ');
            }
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    /**
     * A server that loaded Gloss from {@code plugin.yml} binds the executors directly; one loaded
     * from {@code paper-plugin.yml} has no {@code getCommand}, so the Brigadier registrar is used.
     */
    private void registerCommands() {
        ChatCommands executor = new ChatCommands(plugin, this);
        try {
            for (String name : ChatCommands.COMMANDS) {
                PluginCommand command = plugin.getCommand(name);
                if (command == null) {
                    Gloss.warn("Command '%s' is missing from plugin.yml and was not bound.", name);
                    continue;
                }
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        } catch (UnsupportedOperationException paperPlugin) {
            registerPaperCommands(executor);
        }
    }

    private void registerPaperCommands(ChatCommands executor) {
        try {
            Class<?> registrar = Class.forName("art.arcane.gloss.paper.PaperChatCommandRegistrar",
                true, getClass().getClassLoader());
            registrar.getDeclaredMethod("register", Gloss.class, ChatCommands.class)
                .invoke(null, plugin, executor);
        } catch (ReflectiveOperationException | LinkageError failure) {
            Gloss.logExceptionStack(false, failure,
                "Chat commands could not be registered on this server flavour.");
        }
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, () -> rebuild(registry.snapshot(delta)));
    }

    /**
     * Ids are visited in order so a duplicate alias always refuses the later document, whichever
     * order the watcher reported the files in.
     */
    private void rebuild(Map<String, GlossDocument<ChannelDoc>> documents) {
        List<String> ids = new ArrayList<>(documents.keySet());
        ids.sort(String::compareTo);
        Map<String, ChannelRuntime> byId = new LinkedHashMap<>(ids.size());
        Map<String, ChannelRuntime> byName = new LinkedHashMap<>(ids.size() * 2);
        for (String id : ids) {
            ChannelRuntime runtime = ChannelRuntime.of(id, documents.get(id).value());
            List<String> claims = new ArrayList<>(runtime.aliases().size() + 2);
            claims.add(id.toLowerCase(Locale.ROOT));
            claims.add(runtime.name());
            claims.addAll(runtime.aliases());
            String taken = firstTaken(byName, claims);
            if (taken != null) {
                Gloss.warn("Channel %s claims '%s', already held by %s; the document is ignored.",
                    id, taken, byName.get(taken).id());
                continue;
            }
            byId.put(id, runtime);
            for (String claim : claims) {
                byName.put(claim, runtime);
            }
        }
        Index next = new Index(Map.copyOf(byId), Map.copyOf(byName), selectDefault(byId.values()));
        index = next;
        if (started) {
            Gloss.log(Level.INFO, "Channels: %d loaded, default %s.", byId.size(),
                next.defaultChannel() == null ? "none" : next.defaultChannel().id());
        }
    }

    private static String firstTaken(Map<String, ChannelRuntime> byName, List<String> claims) {
        for (String claim : claims) {
            if (byName.containsKey(claim)) {
                return claim;
            }
        }
        return null;
    }

    /** The channel marked {@code default}, lowest priority first; otherwise the lowest priority. */
    private static ChannelRuntime selectDefault(Iterable<ChannelRuntime> channels) {
        ChannelRuntime marked = null;
        ChannelRuntime fallback = null;
        for (ChannelRuntime channel : channels) {
            if (channel.isDefault() && (marked == null || channel.priority() < marked.priority())) {
                marked = channel;
            }
            if (fallback == null || channel.priority() < fallback.priority()) {
                fallback = channel;
            }
        }
        return marked == null ? fallback : marked;
    }

    private record Index(Map<String, ChannelRuntime> byId, Map<String, ChannelRuntime> byName,
                         ChannelRuntime defaultChannel) {
        private static final Index EMPTY = new Index(Map.of(), Map.of(), null);
    }
}
