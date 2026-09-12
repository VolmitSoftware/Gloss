package art.arcane.gloss.chat;

import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;

/**
 * The message body as one viewer sees it. The player's own text is escaped first, so anything they
 * typed between angle brackets stays literal; the engine's own click and hover tags are the only
 * markup that survives into the render. Mentions are rewritten for the mentioned viewer alone,
 * which is what makes the same message render differently per recipient.
 */
public final class ChatBody {
    private ChatBody() {
    }

    /**
     * @param escape          the MiniMessage escaper of whichever parser will read the result;
     *                        null uses the plugin's own
     * @param interactive     false for a viewer whose client has no click or hover (Bedrock)
     * @param item            the sender's held item, or null when the {@code [item]} token is inert
     */
    public record Context(UnaryOperator<String> escape, String viewerName, boolean mentionsAllowed,
                          boolean linksAllowed, boolean itemsAllowed, boolean interactive, Item item) {
    }

    public record Item(String id, String name, int amount) {
    }

    public record Body(String text, boolean mentioned) {
    }

    public static Body render(ChannelRuntime channel, String message, Context context) {
        String escaped = escaper(context).apply(message == null ? "" : message);
        Matcher matcher = channel.bodyScanner().matcher(escaped);
        StringBuilder output = new StringBuilder(escaped.length() + 32);
        boolean mentioned = false;
        int cursor = 0;
        while (matcher.find()) {
            output.append(escaped, cursor, matcher.start());
            cursor = matcher.end();
            if (matcher.group(ChannelRuntime.LINK_GROUP) != null) {
                output.append(link(channel, context, matcher.group()));
                continue;
            }
            if (matcher.group(ChannelRuntime.ITEM_GROUP) != null) {
                output.append(item(channel, context, matcher.group()));
                continue;
            }
            String name = matcher.group(ChannelRuntime.MENTION_NAME_GROUP);
            if (mentions(channel, context, name)) {
                output.append(mention(channel, name));
                mentioned = true;
                continue;
            }
            output.append(matcher.group());
        }
        return new Body(output.append(escaped, cursor, escaped.length()).toString(), mentioned);
    }

    /** The plugin's own MiniMessage escaper, for the Spigot path and for authored sub-templates. */
    public static String escapeTags(String text) {
        return MiniMessage.miniMessage().escapeTags(text == null ? "" : text);
    }

    private static UnaryOperator<String> escaper(Context context) {
        return context.escape() == null ? ChatBody::escapeTags : context.escape();
    }

    private static boolean mentions(ChannelRuntime channel, Context context, String name) {
        return channel.doc().mentions().enabled() && context.mentionsAllowed()
            && name != null && name.equalsIgnoreCase(context.viewerName());
    }

    private static String mention(ChannelRuntime channel, String name) {
        return TextUtils.toMiniMessage(substitute(channel.doc().mentions().render(), "mention.name", name));
    }

    private static String link(ChannelRuntime channel, Context context, String url) {
        ChannelDoc.Links links = channel.doc().links();
        if (!links.enabled() || !context.linksAllowed()) {
            return url;
        }
        String text = TextUtils.toMiniMessage(
            substitute(substitute(links.render(), "link.host", host(url)), "link.url", url));
        if (!context.interactive()) {
            return text;
        }
        return "<click:open_url:'" + argument(url) + "'>" + text + "</click>";
    }

    private static String item(ChannelRuntime channel, Context context, String token) {
        ChannelDoc.Items items = channel.doc().items();
        Item held = context.item();
        if (!items.enabled() || !context.itemsAllowed() || held == null) {
            return token;
        }
        String label = escapeTags(held.amount() <= 1
            ? "[" + held.name() + "]"
            : "[" + held.name() + "] x" + held.amount());
        if (!context.interactive()) {
            return label;
        }
        return "<hover:show_item:'" + argument(held.id()) + "':" + held.amount() + ">" + label + "</hover>";
    }

    /** The readable host of a link, falling back to the whole token when it does not parse. */
    private static String host(String url) {
        try {
            String host = new URI(url.startsWith("www.") ? "https://" + url : url).getHost();
            return host == null ? url : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException malformed) {
            return url;
        }
    }

    /** Quotes a value for a single-quoted MiniMessage tag argument. */
    private static String argument(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * The mention, link and item render templates are one-variable mini-templates, substituted
     * directly rather than through the expression pipeline: they run once per match per viewer.
     */
    private static String substitute(String template, String name, String value) {
        int open = template.indexOf("{{");
        if (open < 0) {
            return template;
        }
        StringBuilder output = new StringBuilder(template.length() + value.length());
        int cursor = 0;
        while (open >= 0) {
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                break;
            }
            if (!template.substring(open + 2, close).trim().equals(name)) {
                open = template.indexOf("{{", close + 2);
                continue;
            }
            output.append(template, cursor, open).append(value);
            cursor = close + 2;
            open = template.indexOf("{{", cursor);
        }
        return output.append(template, cursor, template.length()).toString();
    }
}
