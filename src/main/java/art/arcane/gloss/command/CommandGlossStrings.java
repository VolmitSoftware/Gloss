package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.strings.StringsCatalog;
import art.arcane.gloss.strings.StringsService;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** {@code /gloss strings} — inspects the authored {@code strings/} catalogs {@code lang()} reads. */
@Director(name = "strings", description = "Authored content strings",
    descriptionKey = "command.help.strings.root")
public class CommandGlossStrings {
    private static final String PERMISSION = "gloss.strings";
    private static final String RESET_PERMISSION = "gloss.strings.reset";
    private static final int MAX_LISTED_KEYS = 40;

    private final Gloss plugin;

    public CommandGlossStrings(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", description = "List loaded string locales and their entry counts",
        descriptionKey = "command.help.strings.list")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, PERMISSION)) {
            return;
        }
        StringsService strings = plugin.service(StringsService.class);
        List<String> locales = strings == null ? List.of() : strings.locales();
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(GlossLocalization.globalText(GlossMessages.STRINGS_LIST_HEADER,
            GlossLocalization.args(MessageArgument.trusted("count", locales.size()))), theme));
        if (locales.isEmpty()) {
            lines.add(GlossLocalization.globalText(GlossMessages.STRINGS_LIST_EMPTY));
        }
        StringsCatalog catalog = strings == null ? StringsCatalog.EMPTY : strings.catalog();
        for (String locale : locales) {
            String fallback = catalog.fallbackOf(locale);
            lines.add("&7- &f" + locale + " &7(" + catalog.entries(locale).size() + " keys"
                + (fallback.isEmpty() ? "" : ", falls back to " + fallback) + ")");
        }
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "missing", description = "List keys en_US carries that a locale does not",
        descriptionKey = "command.help.strings.missing")
    public void missing(@Param(name = "locale", description = "Locale to compare against en_US",
                            descriptionKey = "command.help.strings.missing.locale",
                            customHandler = LocaleHandler.class) String locale,
                        @Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, PERMISSION)) {
            return;
        }
        StringsService strings = plugin.service(StringsService.class);
        List<String> missing = strings == null ? List.of() : strings.missing(locale);
        if (missing.isEmpty()) {
            GlossLocalization.sendGlobal(sender, GlossMessages.STRINGS_MISSING_NONE,
                GlossLocalization.args(MessageArgument.untrusted("locale", locale)));
            return;
        }
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(GlossLocalization.globalText(GlossMessages.STRINGS_MISSING_HEADER,
            GlossLocalization.args(MessageArgument.untrusted("locale", locale),
                MessageArgument.trusted("count", missing.size()))), theme));
        for (String key : missing.subList(0, Math.min(missing.size(), MAX_LISTED_KEYS))) {
            lines.add("&7- &f" + key);
        }
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "reset", sync = true, description = "Restore shipped string documents",
        descriptionKey = "command.help.strings.reset")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*",
                          description = "Name to reset, or * for every shipped default",
                          descriptionKey = "command.help.arg.reset_name") String name) {
        if (GlossCommandMessages.denied(sender, RESET_PERMISSION)) {
            return;
        }
        StringsService strings = plugin.service(StringsService.class);
        GlossCommandMessages.sendResetResult(sender, "strings", name,
            strings == null ? List.of() : strings.resetToDefault(name));
    }

    public static final class LocaleHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            KList<String> locales = new KList<>();
            Gloss plugin = Gloss.instance;
            StringsService strings = plugin == null ? null : plugin.service(StringsService.class);
            if (strings != null) {
                locales.addAll(strings.locales());
            }
            return locales;
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.isBlank()) {
                throw new DirectorParsingException("locale is required");
            }
            return in.strip();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
