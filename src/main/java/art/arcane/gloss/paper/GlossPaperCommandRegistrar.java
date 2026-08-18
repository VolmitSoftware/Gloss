package art.arcane.gloss.paper;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.command.GlossCommandService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;

public final class GlossPaperCommandRegistrar {
    private GlossPaperCommandRegistrar() {
    }

    public static void register(Gloss plugin, GlossCommandService commandService) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(
                    "gloss",
                    "Gloss base command.",
                    List.of("gl", "glo", "gg"),
                    new GlossPaperCommand(commandService, "gloss")
            );
            event.registrar().register(
                    "hologram",
                    "Gloss hologram commands.",
                    List.of("holo", "h"),
                    new GlossPaperCommand(commandService, "hologram")
            );
            event.registrar().register(
                    "board",
                    "Gloss scoreboard commands.",
                    List.of("sb", "bd"),
                    new GlossPaperCommand(commandService, "board")
            );
        });
    }
}
