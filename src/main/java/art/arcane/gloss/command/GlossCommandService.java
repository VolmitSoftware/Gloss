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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class GlossCommandService implements CommandExecutor, TabCompleter, DirectorInvocationHook {
    private static final String ROOT_COMMAND = "gloss";
    private static final String HOLOGRAM_COMMAND = "hologram";
    private static final String BOARD_COMMAND = "board";
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
            "gloss.emoji.use"
    );
    private static final int HELP_PAGE_SIZE = 8;

    private final Gloss plugin;
    private final DirectorTheme theme;
    private final AtomicCache<DirectorRuntimeEngine> directorCache = new AtomicCache<>();

    public GlossCommandService(Gloss plugin) {
        this.plugin = plugin;
        this.theme = DirectorThemes.forProduct(DirectorProduct.GLOSS);
    }

    static DirectorMiniMenu.Theme menuTheme() {
        return DirectorMiniMenu.Theme.fromDirectorTheme(DirectorThemes.forProduct(DirectorProduct.GLOSS));
    }

    public void register() {
        getDirector();
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
        if (!hasBaseCommandAccess(sender)) {
            sender.sendMessage(GlossLocalization.english().legacy(GlossMessages.COMMAND_NO_PERMISSION_USE));
            playFailureChime(sender);
            return true;
        }

        if (sendHelpIfRequested(sender, routed)) {
            playInfoChime(sender);
            return true;
        }

        DirectorExecutionResult result = runDirector(sender, label, routed);
        if (result.isSuccess()) {
            playSuccessChime(sender);
            return true;
        }

        sender.sendMessage(GlossLocalization.english().legacy(
                GlossMessages.COMMAND_USAGE_HELP,
                GlossLocalization.args(MessageArgument.trusted("command", commandName))
        ));
        playFailureChime(sender);
        return true;
    }

    public List<String> tabComplete(CommandSender sender, String commandName, String[] args) {
        if (!hasBaseCommandAccess(sender)) {
            return List.of();
        }
        return runDirectorTab(sender, commandName, routedArgs(commandName, args, true));
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
        return directorCache.aquire(this::buildDirector);
    }

    private DirectorRuntimeEngine buildDirector() {
        return DirectorEngineFactory.create(
                new CommandGloss(plugin),
                DirectorEngineOptions.builder()
                        .contexts(buildDirectorContexts())
                        .dispatcher(this::dispatchDirector)
                        .invocationHook(this)
                        .textResolver(GlossLocalization.english()::directorText)
                        .build()
        );
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
            plugin.getLogger().log(Level.SEVERE, "Director command execution failed", e);
            return DirectorExecutionResult.notHandled();
        }
    }

    private List<String> runDirectorTab(CommandSender sender, String alias, String[] args) {
        try {
            return getDirector().tabComplete(new DirectorInvocation(new BukkitDirectorSender(sender), alias, Arrays.asList(args)));
        } catch (Throwable e) {
            plugin.getLogger().log(Level.WARNING, "Director tab completion failed", e);
            return List.of();
        }
    }

    private boolean sendHelpIfRequested(CommandSender sender, String[] args) {
        Optional<DirectorMiniMenu.DirectorHelpPage> page = DirectorMiniMenu.resolveHelp(
                getDirector(),
                Arrays.asList(normalizeHelpArgs(args)),
                HELP_PAGE_SIZE
        );
        if (page.isEmpty()) {
            return false;
        }

        DirectorMiniMenu.Theme helpTheme = DirectorMiniMenu.Theme.fromDirectorTheme(theme);
        DirectorMiniMenu.deliver(sender, DirectorMiniMenu.render(page.get(), helpTheme, GlossLocalization.english().directorResolver()));

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
        GlossConfig config = plugin.cfg();
        return config == null || config.commands().sounds();
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
                sender.sendMessage(message);
            }
        }
    }

    private static final class AtomicCache<T> {
        private final AtomicReference<T> reference = new AtomicReference<>();

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
