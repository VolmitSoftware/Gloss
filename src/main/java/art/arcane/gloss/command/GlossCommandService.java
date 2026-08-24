package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.compat.BukkitDirectorContext;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionMode;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorInvocationHook;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.director.theme.DirectorProduct;
import art.arcane.volmlib.util.director.theme.DirectorTheme;
import art.arcane.volmlib.util.director.theme.DirectorThemes;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class GlossCommandService implements CommandExecutor, TabCompleter, DirectorInvocationHook {
    private static final String ROOT_COMMAND = "gloss";
    private static final String HOLOGRAM_COMMAND = "hologram";
    private static final String BOARD_COMMAND = "board";
    private static final DirectorMiniMenu.Theme MENU_THEME = new DirectorMiniMenu.Theme(
            "#AA00AA",
            "#FF55FF",
            "#555555",
            "#555555",
            "#AAAAAA",
            "#FF5555",
            "#FF55FF",
            "#555555"
    );
    private static final List<String> BASE_COMMAND_PERMISSIONS = List.of(
            "gloss.admin",
            "gloss.holograms",
            "gloss.holograms.create",
            "gloss.holograms.edit",
            "gloss.holograms.delete",
            "gloss.holograms.move",
            "gloss.holograms.teleport",
            "gloss.boards",
            "gloss.boards.create",
            "gloss.boards.edit",
            "gloss.boards.delete",
            "gloss.boards.show",
            "gloss.boards.hide",
            "gloss.emoji.use",
            "gloss.emoji.reset",
            "gloss.animations.reset",
            "gloss.bubbles.reset",
            "gloss.bubbles.style",
            "gloss.tablist.reset",
            "gloss.motd.reset",
            "gloss.drops.reset",
            "gloss.menus",
            "gloss.menus.list",
            "gloss.menus.open",
            "gloss.menus.close",
            "gloss.menus.move",
            "gloss.menus.back",
            "gloss.menus.create",
            "gloss.menus.edit",
            "gloss.panels",
            "gloss.previews",
            "gloss.previews.reset",
            "gloss.previews.dump",
            "gloss.items",
            "gloss.items.export",
            "gloss.web",
            "gloss.web.open",
            "gloss.web.edit",
            "gloss.web.workspace",
            "gloss.web.sessions",
            "gloss.import",
            "gloss.import.apply"
    );
    /**
     * The ported subtrees keep HoloUi's positional-to-keyed convenience pre-pass. Everything
     * else on /gloss (hologram, board, emoji, ...) stays strictly keyed per the Director law.
     */
    private static final Set<String> SCOPED_POSITIONAL_ROOTS = Set.of(
            "menu", "menus", "panel", "panels", "preview", "previews", "item", "items", "web", "import"
    );
    private final Gloss plugin;
    private final DirectorTheme theme;
    private final AtomicCache<Tree> directorCache = new AtomicCache<>();

    public GlossCommandService(Gloss plugin) {
        this.plugin = plugin;
        this.theme = DirectorThemes.forProduct(DirectorProduct.GLOSS);
    }

    static DirectorMiniMenu.Theme menuTheme() {
        return MENU_THEME;
    }

    public void register() {
        tree().root().enable();
        PluginCommand rootCommand;
        try {
            rootCommand = plugin.getCommand(ROOT_COMMAND);
        } catch (UnsupportedOperationException ignored) {
            registerPaperCommands();
            return;
        }

        bind(rootCommand, ROOT_COMMAND);
        bind(plugin.getCommand(HOLOGRAM_COMMAND), HOLOGRAM_COMMAND);
        bind(plugin.getCommand(BOARD_COMMAND), BOARD_COMMAND);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String commandName = normalizeCommandName(command.getName());
        if (commandName == null) {
            return false;
        }
        return executeCommand(sender, commandName, label, args);
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        String commandName = normalizeCommandName(command.getName());
        if (commandName == null) {
            return List.of();
        }
        return tabComplete(sender, commandName, args);
    }

    public boolean executeCommand(CommandSender sender, String commandName, String label, String[] args) {
        String[] routed = routedArgs(commandName, args, false);
        if (isScopedPositionalRoot(routed)) {
            routed = normalizePositionalArgs(routed);
        }
        if (!hasBaseCommandAccess(sender)) {
            GlossLocalization.sendGlobal(sender, GlossMessages.COMMAND_NO_PERMISSION_USE);
            playFailureChime(sender);
            return true;
        }

        if (sendHelpIfRequested(sender, routed)) {
            playInfoChime(sender);
            return true;
        }

        DirectorExecutionResult result = runDirector(sender, label, routed);
        if (result.isSuccess()) {
            if (!defersAutomaticOutcomeSound(routed)) {
                playSuccessChime(sender);
            }
            return true;
        }

        GlossLocalization.sendGlobal(
            sender,
            GlossMessages.COMMAND_USAGE_HELP,
            GlossLocalization.args(MessageArgument.trusted("command", commandName))
        );
        playFailureChime(sender);
        return true;
    }

    public List<String> tabComplete(CommandSender sender, String commandName, String[] args) {
        if (!hasBaseCommandAccess(sender)) {
            return List.of();
        }
        String[] routed = routedArgs(commandName, args, true);
        boolean scoped = isScopedPositionalRoot(routed);
        String[] normalized = scoped ? normalizeTabArgs(routed) : routed;
        if (!canCompleteRoute(sender, normalized)) {
            return List.of();
        }
        List<String> suggestions = filterWebCompletions(
                sender, routed, runDirectorTab(sender, commandName, normalized));
        return scoped ? restorePositionalSuggestions(routed, suggestions) : suggestions;
    }

    @Override
    public void beforeInvoke(DirectorInvocation invocation, DirectorRuntimeNode node) {
        if (invocation.getSender() instanceof BukkitDirectorSender sender) {
            BukkitDirectorContext.touch(sender.sender());
        }
    }

    @Override
    public void afterInvoke(DirectorInvocation invocation, DirectorRuntimeNode node) {
        BukkitDirectorContext.remove();
    }

    static boolean hasBaseCommandAccess(CommandSender sender) {
        if (sender == null) {
            return false;
        }
        for (String permission : BASE_COMMAND_PERMISSIONS) {
            if (sender.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    static String[] routedArgs(String commandName, String[] args, boolean forCompletion) {
        String[] safeArgs = args == null ? new String[0] : args;
        String prefix = switch (commandName) {
            case HOLOGRAM_COMMAND -> HOLOGRAM_COMMAND;
            case BOARD_COMMAND -> BOARD_COMMAND;
            default -> null;
        };

        if (prefix == null) {
            return safeArgs;
        }

        int extra = forCompletion && safeArgs.length == 0 ? 1 : 0;
        String[] routed = new String[safeArgs.length + 1 + extra];
        routed[0] = prefix;
        System.arraycopy(safeArgs, 0, routed, 1, safeArgs.length);
        if (extra > 0) {
            routed[routed.length - 1] = "";
        }
        return routed;
    }

    static String[] normalizeHelpArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }

        List<String> normalized = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!isHelpWord(arg)) {
                normalized.add(arg);
                continue;
            }

            String page = "1";
            if (i + 1 < args.length && isPageToken(args[i + 1])) {
                page = args[i + 1].trim();
                i++;
            }
            normalized.add("help=" + page);
        }

        return normalized.toArray(new String[0]);
    }

    static boolean isScopedPositionalRoot(String[] args) {
        return args != null && args.length > 0 && args[0] != null
                && SCOPED_POSITIONAL_ROOTS.contains(args[0].toLowerCase(Locale.ROOT));
    }

    static String[] normalizePositionalArgs(String[] args) {
        args = normalizeTrailingText(args);

        if (args.length == 1 && isGroup(args[0], "menu", "menus")) {
            return new String[]{"menu", "list"};
        }
        if (args.length == 1 && isGroup(args[0], "panel", "panels")) {
            return new String[]{"panel", "list"};
        }
        if (args.length == 1 && isGroup(args[0], "preview", "previews")) {
            return new String[]{"preview", "list"};
        }

        if (args.length == 3
                && isGroup(args[0], "menu", "menus")
                && args[1].equalsIgnoreCase("open")
                && isBareOptionalValue(args[2])) {
            return new String[]{args[0], "open", "menu=" + args[2]};
        }
        if (args.length == 3
                && isGroup(args[0], "preview", "previews")
                && args[1].equalsIgnoreCase("reset")
                && isBareOptionalValue(args[2])) {
            return new String[]{args[0], "reset", "name=" + args[2]};
        }
        if (args.length == 3
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("near")
                && isBareOptionalValue(args[2])) {
            return new String[]{args[0], "near", "radius=" + args[2]};
        }
        if (args.length == 3
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("list")
                && isBareOptionalValue(args[2])) {
            return new String[]{args[0], "list", "page=" + args[2]};
        }
        if (args.length == 4
                && args[0].equalsIgnoreCase("web")
                && args[1].equalsIgnoreCase("sessions")
                && args[2].equalsIgnoreCase("list")
                && isBareOptionalValue(args[3])) {
            return new String[]{args[0], args[1], args[2], "page=" + args[3]};
        }
        if (args.length == 4
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("create")
                && isBareOptionalValue(args[3])) {
            return new String[]{args[0], "create", args[2], "menu=" + args[3]};
        }

        return args;
    }

    private static String[] normalizeTrailingText(String[] args) {
        if (args.length < 2) {
            return args;
        }

        if (isGroup(args[0], "menu", "menus") && args[1].equalsIgnoreCase("create")) {
            if (args.length == 3) {
                return new String[]{args[0], args[1], args[2], "text="};
            }
            if (args.length > 3) {
                String joined = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                String trailingText = joined.regionMatches(true, 0, "text=", 0, "text=".length())
                        ? joined
                        : "text=" + joined;
                return new String[]{args[0], args[1], args[2], trailingText};
            }
            return args;
        }

        if (!isGroup(args[0], "menu", "menus") && !isGroup(args[0], "panel", "panels")) {
            return args;
        }

        TrailingArgument trailing = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "addrow" -> new TrailingArgument(3, "text");
            case "insertrow", "setrow" -> new TrailingArgument(4, "text");
            case "seticon", "style" -> new TrailingArgument(5, "value");
            case "image" -> new TrailingArgument(3, "path");
            default -> null;
        };
        if (trailing == null || args.length <= trailing.index()) {
            return args;
        }

        String prefix = trailing.name() + "=";
        String joined = String.join(" ", Arrays.copyOfRange(args, trailing.index(), args.length));
        String trailingArgument = joined.regionMatches(true, 0, prefix, 0, prefix.length())
                ? joined
                : prefix + joined;
        String[] normalized = Arrays.copyOf(args, trailing.index() + 1);
        normalized[trailing.index()] = trailingArgument;
        return normalized;
    }

    static String[] normalizeTabArgs(String[] args) {
        if (args.length == 3
                && isGroup(args[0], "menu", "menus")
                && args[1].equalsIgnoreCase("open")
                && isBareTabValue(args[2])) {
            return new String[]{args[0], "open", "menu=" + args[2]};
        }
        if (args.length == 3
                && isGroup(args[0], "preview", "previews")
                && args[1].equalsIgnoreCase("reset")
                && isBareTabValue(args[2])) {
            return new String[]{args[0], "reset", "name=" + args[2]};
        }
        if (args.length == 3
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("near")
                && isBareTabValue(args[2])) {
            return new String[]{args[0], "near", "radius=" + args[2]};
        }
        if (args.length == 3
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("list")
                && isBareTabValue(args[2])) {
            return new String[]{args[0], "list", "page=" + args[2]};
        }
        if (args.length == 4
                && args[0].equalsIgnoreCase("web")
                && args[1].equalsIgnoreCase("sessions")
                && args[2].equalsIgnoreCase("list")
                && isBareTabValue(args[3])) {
            return new String[]{args[0], args[1], args[2], "page=" + args[3]};
        }
        if (args.length == 4
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("create")
                && isBareTabValue(args[3])) {
            return new String[]{args[0], "create", args[2], "menu=" + args[3]};
        }

        return args;
    }

    static boolean defersAutomaticOutcomeSound(String[] normalizedArgs) {
        return normalizedArgs.length > 1
                && isGroup(normalizedArgs[0], "menu", "menus")
                && normalizedArgs[1].equalsIgnoreCase("create");
    }

    private static boolean canCompleteRoute(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("web")) {
            return true;
        }
        String permission = webChildPermission(args[1]);
        return permission == null || sender.hasPermission(permission);
    }

    private static List<String> filterWebCompletions(CommandSender sender, String[] args,
                                                     List<String> suggestions) {
        if (args.length <= 1) {
            if (hasAnyWebPermission(sender)) {
                return suggestions;
            }
            return suggestions.stream()
                    .filter(suggestion -> !suggestion.equalsIgnoreCase("web"))
                    .toList();
        }
        if (!args[0].equalsIgnoreCase("web") || args.length != 2) {
            return suggestions;
        }
        return suggestions.stream()
                .filter(suggestion -> {
                    String permission = webChildPermission(suggestion);
                    return permission == null || sender.hasPermission(permission);
                })
                .toList();
    }

    private static boolean hasAnyWebPermission(CommandSender sender) {
        return sender.hasPermission(CommandGlossWeb.OPEN_PERMISSION)
                || sender.hasPermission(CommandGlossWeb.EDIT_PERMISSION)
                || sender.hasPermission(CommandGlossWeb.WORKSPACE_PERMISSION)
                || sender.hasPermission(CommandGlossWebSessions.PERMISSION);
    }

    private static String webChildPermission(String child) {
        if (child == null) {
            return null;
        }
        return switch (child.toLowerCase(Locale.ROOT)) {
            case "open" -> CommandGlossWeb.OPEN_PERMISSION;
            case "edit" -> CommandGlossWeb.EDIT_PERMISSION;
            case "workspace" -> CommandGlossWeb.WORKSPACE_PERMISSION;
            case "sessions" -> CommandGlossWebSessions.PERMISSION;
            default -> null;
        };
    }

    static List<String> restorePositionalSuggestions(String[] args, List<String> suggestions) {
        String prefix = positionalPrefix(args);
        if (prefix == null) {
            return suggestions;
        }

        return suggestions.stream()
                .map(suggestion -> suggestion.startsWith(prefix) ? suggestion.substring(prefix.length()) : suggestion)
                .toList();
    }

    private static boolean isBareOptionalValue(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (token.indexOf('=') >= 0) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return !lower.equals("help")
                && !lower.equals("?")
                && !lower.startsWith("help=");
    }

    private static boolean isBareTabValue(String token) {
        return token != null && token.indexOf('=') < 0 && (token.isEmpty() || isBareOptionalValue(token));
    }

    private static String positionalPrefix(String[] args) {
        if (args.length == 3
                && isGroup(args[0], "menu", "menus")
                && args[1].equalsIgnoreCase("open")
                && isBareTabValue(args[2])) {
            return "menu=";
        }
        if (args.length == 3
                && isGroup(args[0], "preview", "previews")
                && args[1].equalsIgnoreCase("reset")
                && isBareTabValue(args[2])) {
            return "name=";
        }
        if (args.length == 3
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("near")
                && isBareTabValue(args[2])) {
            return "radius=";
        }
        if (args.length == 3
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("list")
                && isBareTabValue(args[2])) {
            return "page=";
        }
        if (args.length == 4
                && args[0].equalsIgnoreCase("web")
                && args[1].equalsIgnoreCase("sessions")
                && args[2].equalsIgnoreCase("list")
                && isBareTabValue(args[3])) {
            return "page=";
        }
        if (args.length == 4
                && isGroup(args[0], "panel", "panels")
                && args[1].equalsIgnoreCase("create")
                && isBareTabValue(args[3])) {
            return "menu=";
        }
        return null;
    }

    private static boolean isGroup(String value, String canonical, String alias) {
        return value.equalsIgnoreCase(canonical) || value.equalsIgnoreCase(alias);
    }

    private record TrailingArgument(int index, String name) {
    }

    private static String normalizeCommandName(String commandName) {
        if (commandName == null) {
            return null;
        }

        String lowered = commandName.toLowerCase(Locale.ROOT);
        return switch (lowered) {
            case ROOT_COMMAND, HOLOGRAM_COMMAND, BOARD_COMMAND -> lowered;
            default -> null;
        };
    }

    private static boolean isHelpWord(String value) {
        return value != null && (value.equalsIgnoreCase("help") || value.equals("?"));
    }

    private static boolean isPageToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void bind(PluginCommand command, String commandName) {
        if (command == null) {
            Gloss.warn("Command '" + commandName + "' is missing from plugin.yml and was not bound.");
            return;
        }

        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    private void registerPaperCommands() {
        try {
            Class<?> registrarType = Class.forName(
                    "art.arcane.gloss.paper.GlossPaperCommandRegistrar",
                    true,
                    getClass().getClassLoader()
            );
            Method register = registrarType.getDeclaredMethod("register", Gloss.class, GlossCommandService.class);
            register.invoke(null, plugin, this);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("Paper command registration failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Paper command registrar is unavailable", exception);
        } catch (LinkageError error) {
            throw new IllegalStateException("Paper command APIs are unavailable", error);
        }
    }

    private DirectorRuntimeEngine getDirector() {
        return tree().engine();
    }

    private Tree tree() {
        return directorCache.aquire(this::buildDirector);
    }

    public void shutdown() {
        Tree current = directorCache.peek();
        if (current != null) {
            current.root().shutdown();
        }
    }

    /**
     * The command tree and its engine are cached as one value. Splitting them let a racing builder
     * publish its own tree into a field while the cache kept the winner's engine, leaving the two
     * halves describing different objects — and the loser's tree holding live listeners nothing
     * could ever shut down.
     */
    private Tree buildDirector() {
        CommandGloss built = new CommandGloss(plugin);
        return new Tree(built, DirectorEngineFactory.create(
                built,
                DirectorEngineOptions.builder()
                        .contexts(buildDirectorContexts())
                        .dispatcher(this::dispatchDirector)
                        .invocationHook(this)
                        .textResolver(GlossLocalization.globalDirectorResolver())
                        .build()
        ));
    }

    private record Tree(CommandGloss root, DirectorRuntimeEngine engine) {
    }

    private DirectorContextRegistry buildDirectorContexts() {
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, map) -> {
            if (invocation.getSender() instanceof BukkitDirectorSender sender) {
                return sender.sender();
            }
            return null;
        });
        contexts.register(Player.class, (invocation, map) -> {
            if (invocation.getSender() instanceof BukkitDirectorSender sender && sender.sender() instanceof Player player) {
                return player;
            }
            return null;
        });
        return contexts;
    }

    private void dispatchDirector(DirectorExecutionMode mode, Runnable runnable) {
        runnable.run();
    }

    private DirectorExecutionResult runDirector(CommandSender sender, String label, String[] args) {
        try {
            return getDirector().execute(new DirectorInvocation(new BukkitDirectorSender(sender), label, Arrays.asList(args)));
        } catch (Throwable e) {
            Gloss.logExceptionStack(true, e, "Director command execution failed.");
            return DirectorExecutionResult.notHandled();
        }
    }

    private List<String> runDirectorTab(CommandSender sender, String alias, String[] args) {
        try {
            return getDirector().tabComplete(new DirectorInvocation(new BukkitDirectorSender(sender), alias, Arrays.asList(args)));
        } catch (Throwable e) {
            Gloss.logExceptionStack(false, e, "Director tab completion failed.");
            return List.of();
        }
    }

    private boolean sendHelpIfRequested(CommandSender sender, String[] args) {
        List<String> helpArgs = Arrays.asList(normalizeHelpArgs(args));
        Optional<DirectorMiniMenu.DirectorHelpPage> page = DirectorMiniMenu.resolveHelp(getDirector(), helpArgs);
        if (page.isEmpty()) {
            return false;
        }

        DirectorMiniMenu.deliver(sender, page.get(), MENU_THEME, GlossLocalization.globalDirectorResolver());

        return true;
    }

    private void playSuccessChime(CommandSender sender) {
        if (soundsEnabled() && sender instanceof Player player) {
            player.playSound(player.getLocation(), theme.getSuccessSound(), SoundCategory.MASTER, 0.5f, 1.5f);
        }
    }

    private void playFailureChime(CommandSender sender) {
        if (soundsEnabled() && sender instanceof Player player) {
            player.playSound(player.getLocation(), theme.getErrorSound(), SoundCategory.MASTER, 0.4f, 0.6f);
        }
    }

    private void playInfoChime(CommandSender sender) {
        if (soundsEnabled() && sender instanceof Player player) {
            player.playSound(player.getLocation(), theme.getSuccessSound(), SoundCategory.MASTER, 0.4f, 1.0f);
        }
    }

    private boolean soundsEnabled() {
        return soundsEnabled(plugin.cfg());
    }

    public static boolean soundsEnabled(GlossConfig config) {
        return config == null || config.commands().sounds();
    }

    public static boolean commandSoundsEnabled() {
        return soundsEnabled(GlossConfig.current());
    }

    private record BukkitDirectorSender(CommandSender sender) implements DirectorSender {
        @Override
        public String getName() {
            return sender.getName();
        }

        @Override
        public boolean isPlayer() {
            return sender instanceof Player;
        }

        @Override
        public void sendMessage(String message) {
            if (message != null && !message.trim().isEmpty()) {
                ComponentMessenger.sendLiteral(sender, message);
            }
        }
    }

    private static final class AtomicCache<T> {
        private final AtomicReference<T> reference = new AtomicReference<>();

        T peek() {
            return reference.get();
        }

        T aquire(Supplier<T> supplier) {
            T value = reference.get();
            if (value != null) {
                return value;
            }

            T computed = supplier.get();
            if (reference.compareAndSet(null, computed)) {
                return computed;
            }

            T existing = reference.get();
            return existing != null ? existing : computed;
        }
    }
}
