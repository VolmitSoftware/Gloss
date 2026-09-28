package art.arcane.gloss.chat;

import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The plugin's own side of the Adventure relocation boundary. A Paper bridge hands over a server
 * component as JSON, because JSON is the only shape both copies of Adventure agree on.
 */
public final class ChatComponents {
    private static final MiniMessage SCOPED_MARKUP = MiniMessage.builder().strict(true).build();

    private ChatComponents() {
    }

    public static String scopedMarkup(String value) {
        return SCOPED_MARKUP.serialize(TextUtils.parse(value));
    }

    public static String plainText(String json) {
        Component component = GsonComponentSerializer.gson().deserialize(json);
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
