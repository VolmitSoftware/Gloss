package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.bedrock.BedrockService;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.util.common.TextUtils;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * One message as one viewer reads it, produced as a MiniMessage string.
 *
 * <p>The authored format is rendered first with the body and the hover card standing in as private
 * markers, then translated to MiniMessage, and only then do the markers become their content. That
 * ordering is what keeps a player's own text out of the markup pass: the format's colour codes are
 * translated while the body is not yet present, and the body is spliced in already escaped.
 */
public final class ChatMessageRenderer {
    /**
     * Permanent noncharacters: no glyph the forge allocates lands here (it allocates from the
     * private-use area) and no client sends them, so a player cannot type the renderer's own
     * markers into their message.
     */
    static final String MESSAGE_MARKER = "\uFDD0\uFDD1";
    static final String CARD_MARKER = "\uFDD0\uFDD2";

    private final Gloss plugin;
    private final BoundedConditionErrorCallback errors;

    public ChatMessageRenderer(Gloss plugin) {
        this.plugin = plugin;
        this.errors = BoundedConditionErrorCallback.bounded(4, error ->
            Gloss.logExceptionStackThrottled(false, "chat-condition:" + error.source(), error.cause(),
                "Channel condition %s failed and was treated as false.", error.source()));
    }

    public record Rendered(String miniMessage, boolean mentioned) {
    }

    public Rendered render(ChannelRuntime channel, Player sender, Player viewer, String message,
                           ChatContext context) {
        ChatScope scope = scope(channel, sender, viewer);
        ChatBody.Body body = ChatBody.render(channel, message, bodyContext(channel, sender, viewer, context));
        String format = channel.format(scope, errors);
        String rendered = plugin.text().renderScoped(viewer, format, scope, UnaryOperator.identity());
        return new Rendered(splice(TextUtils.toMiniMessage(rendered), body.text(),
            card(channel, viewer, scope)), body.mentioned());
    }

    /**
     * One left-to-right pass, so text that is spliced in is never scanned again: a body that
     * happens to carry a marker must not pull the hover card into the middle of itself.
     */
    static String splice(String template, String body, String card) {
        StringBuilder out = new StringBuilder(template.length() + body.length() + card.length());
        int cursor = 0;
        while (cursor < template.length()) {
            int message = template.indexOf(MESSAGE_MARKER, cursor);
            int hover = template.indexOf(CARD_MARKER, cursor);
            int next = message < 0 ? hover : hover < 0 ? message : Math.min(message, hover);
            if (next < 0) {
                break;
            }
            out.append(template, cursor, next);
            boolean isMessage = next == message;
            out.append(isMessage ? body : card);
            cursor = next + (isMessage ? MESSAGE_MARKER : CARD_MARKER).length();
        }
        return out.append(template, cursor, template.length()).toString();
    }

    private ChatScope scope(ChannelRuntime channel, Player sender, Player viewer) {
        Map<String, Object> values = new HashMap<>(4);
        values.put("message", MESSAGE_MARKER);
        values.put("card", CARD_MARKER);
        values.put("channel.id", channel.id());
        values.put("channel.name", channel.name());
        return new ChatScope(plugin, viewer, sender, values);
    }

    private ChatBody.Context bodyContext(ChannelRuntime channel, Player sender, Player viewer,
                                         ChatContext context) {
        ChannelDoc doc = channel.doc();
        return new ChatBody.Context(context.escape(), viewer == null ? "" : viewer.getName(),
            sender != null && sender.hasPermission(doc.mentions().permission()),
            true,
            sender != null && sender.hasPermission(doc.items().permission()),
            !isBedrock(viewer), context.item());
    }

    /**
     * The card renders per viewer and is spliced into a single-quoted MiniMessage argument, so its
     * quotes and backslashes are escaped after the colour translation, not before.
     */
    private String card(ChannelRuntime channel, Player viewer, ChatScope scope) {
        List<String> lines = channel.doc().card();
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder card = new StringBuilder(lines.size() * 24);
        for (String line : lines) {
            if (!card.isEmpty()) {
                card.append("<newline>");
            }
            card.append(TextUtils.toMiniMessage(
                plugin.text().renderScoped(viewer, line, scope, UnaryOperator.identity())));
        }
        return card.toString().replace("\\", "\\\\").replace("'", "\\'");
    }

    private boolean isBedrock(Player viewer) {
        BedrockService bedrock = plugin.bedrock();
        return viewer != null && bedrock != null && bedrock.isBedrock(viewer.getUniqueId());
    }
}
