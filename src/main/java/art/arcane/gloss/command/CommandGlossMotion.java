package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.motion.MotionDoc;
import art.arcane.gloss.motion.MotionService;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Director(name = "motion", aliases = {"motions"}, descriptionKey = "command.help.motion.root", description = "Inspect motion clips")
public class CommandGlossMotion {
    private final Gloss plugin;

    public CommandGlossMotion(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.motion.list", description = "List motion clips")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        MotionService service = service();
        List<String> ids = service == null ? List.of() : new ArrayList<>(service.ids());
        if (ids.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.MOTION_LIST_EMPTY);
            return;
        }
        ids.sort(String::compareTo);
        GlossCommandMessages.send(sender, GlossMessages.MOTION_LIST,
            MessageArgument.trusted("count", ids.size()),
            MessageArgument.untrusted("value", String.join(", ", ids)));
    }

    @Director(name = "info", descriptionKey = "command.help.motion.info", description = "Show a motion clip's duration, loop, fps and tracks")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.motion.info.id", description = "Motion id") String id) {
        MotionService service = service();
        Optional<MotionDoc> doc = service == null ? Optional.empty() : service.doc(id);
        if (doc.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.MOTION_UNKNOWN, MessageArgument.untrusted("id", id));
            return;
        }
        MotionDoc motion = doc.get();
        GlossCommandMessages.send(sender, GlossMessages.MOTION_INFO,
            MessageArgument.untrusted("id", id),
            MessageArgument.trusted("value", motion.durationTicks()),
            MessageArgument.trusted("kind", motion.loop().toLowerCase(Locale.ROOT)),
            MessageArgument.trusted("count", motion.fps()));
        for (MotionDoc.MotionTrack track : motion.tracks()) {
            GlossCommandMessages.send(sender, GlossMessages.MOTION_TRACK,
                MessageArgument.untrusted("name", track.bone()),
                MessageArgument.trusted("key", track.channel()),
                MessageArgument.trusted("count", track.keyframes().size()));
        }
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.motion.reset", description = "Restore the shipped motion documents")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name", description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.motion.reset")) {
            return;
        }
        MotionService service = service();
        GlossCommandMessages.sendResetResult(sender, "motion", name,
            service == null ? List.of() : service.resetToDefault(name));
    }

    private MotionService service() {
        return plugin == null ? null : plugin.service(MotionService.class);
    }
}
