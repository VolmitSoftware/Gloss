package art.arcane.gloss.paper;

import art.arcane.gloss.Gloss;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

public final class PaperTabCompleteListener implements Listener {
    private final Gloss plugin;

    public PaperTabCompleteListener(Gloss plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (!plugin.cfg().emoji().enabled() || !plugin.cfg().emoji().tabComplete()) {
            return;
        }
        if (event.isCommand()) {
            return;
        }

        String buffer = event.getBuffer();
        int lastSpace = buffer.lastIndexOf(' ');
        String lastWord = lastSpace >= 0 ? buffer.substring(lastSpace + 1) : buffer;
        if (!lastWord.startsWith(":")) {
            return;
        }

        List<String> suggestions = plugin.emoji().suggestions(lastWord);
        if (suggestions.isEmpty()) {
            return;
        }

        List<String> completions = new ArrayList<>(event.getCompletions());
        completions.addAll(suggestions);
        event.setCompletions(completions);
    }
}
