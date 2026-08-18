package art.arcane.gloss.locale;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import net.md_5.bungee.api.ChatColor;

public final class GlossLocalization {
    private static final GlossLocalization ENGLISH = new GlossLocalization();

    private final LocalizationManager manager;

    public GlossLocalization() {
        manager = new LocalizationManager(LocalizationCandidate.english(
                GlossMessages.catalog(),
                PluralSelector.oneOther()
        ));
    }

    public static GlossLocalization english() {
        return ENGLISH;
    }

    public static MessageArgs args(MessageArgument... arguments) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument argument : arguments) {
            builder.add(argument);
        }
        return builder.build();
    }

    public LocalizationSnapshot snapshot() {
        return manager.snapshot();
    }

    public DirectorTextResolver directorResolver() {
        return this::directorText;
    }

    public String directorText(TextKey key, MessageArgs arguments) {
        MessageKey definition = manager.snapshot().catalog().key(key.id());
        if (!(definition instanceof TextKey textKey)) {
            return DirectorTextResolver.ENGLISH.resolve(key, arguments);
        }
        ResolvedText resolved = manager.snapshot().resolve(textKey, arguments == null ? MessageArgs.empty() : arguments);
        return substitute(resolved.template(), resolved.arguments());
    }

    public String legacy(TextKey key) {
        return legacy(key, MessageArgs.empty());
    }

    public String legacy(TextKey key, MessageArgs arguments) {
        ResolvedText resolved = manager.snapshot().resolve(key, arguments);
        return ChatColor.translateAlternateColorCodes('&', substitute(resolved.template(), resolved.arguments()));
    }

    private String substitute(String template, MessageArgs arguments) {
        StringBuilder rendered = new StringBuilder(template.length() + arguments.size() * 8);
        for (int index = 0; index < template.length(); index++) {
            char current = template.charAt(index);
            if (current == '{' && index + 1 < template.length() && template.charAt(index + 1) == '{') {
                rendered.append('{');
                index++;
                continue;
            }
            if (current == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                rendered.append('}');
                index++;
                continue;
            }
            if (current != '{') {
                rendered.append(current);
                continue;
            }
            int end = template.indexOf('}', index + 1);
            if (end < 0) {
                rendered.append(template, index, template.length());
                break;
            }
            String name = template.substring(index + 1, end);
            MessageArgument argument = arguments.require(name);
            String value = String.valueOf(argument.value());
            rendered.append(argument.kind() == MessageArgumentKind.UNTRUSTED ? sanitizeUntrusted(value) : value);
            index = end;
        }
        return rendered.toString();
    }

    private String sanitizeUntrusted(String value) {
        return value.replace('§', '&');
    }
}
