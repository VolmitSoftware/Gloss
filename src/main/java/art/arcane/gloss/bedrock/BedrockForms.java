package art.arcane.gloss.bedrock;

import art.arcane.gloss.dialog.DialogRuntime;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A Bedrock viewer's version of a dialog. The one implementation goes through Geyser's Cumulus API
 * reflectively; the interface exists so the mapping and the dialog service can be exercised without
 * Geyser anywhere near the classpath.
 */
public interface BedrockForms {
    /**
     * @param onSubmit receives the button index under {@link #BUTTON} plus one entry per input key
     * @param onClose  runs when the viewer dismissed the form without answering
     * @return false when no form could be sent, which sends the viewer to the dialog's fallback
     */
    boolean send(Player viewer, DialogRuntime.Rendered rendered, Consumer<Map<String, Object>> onSubmit,
                 Runnable onClose);

    /** The key the chosen button index arrives under, matching the packet path's click index. */
    String BUTTON = "button";
}
