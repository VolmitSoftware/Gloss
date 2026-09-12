package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.camera.CameraService;
import art.arcane.gloss.camera.Spline;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Director(name = "camera", aliases = {"cam"}, descriptionKey = "command.help.camera.root",
    description = "Camera ride tools")
public final class CommandGlossCamera {
    private static final int DEMO_NODES = 8;
    private static final double DEMO_RADIUS = 12.0D;
    private static final double DEMO_HEIGHT = 6.0D;

    private final Gloss plugin;

    public CommandGlossCamera(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "test", sync = true, descriptionKey = "command.help.camera.test",
        description = "Fly a demonstration ride around yourself")
    public void test(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "value", defaultValue = "8", descriptionKey = "command.help.camera.arg.seconds",
                         description = "Ride length in seconds") int value) {
        if (GlossCommandMessages.denied(sender, "gloss.camera.test")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_PLAYERS_ONLY);
            return;
        }
        CameraService camera = plugin.service(CameraService.class);
        if (camera == null || !camera.enabled()) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_DISABLED);
            return;
        }
        if (!camera.ride(player, demoPath(player.getLocation(), Math.max(1, value)),
            new CameraService.Options(true, false))) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_CAMERA_REFUSED);
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_CAMERA_STARTED);
    }

    @Director(name = "stop", sync = true, descriptionKey = "command.help.camera.stop",
        description = "End a camera ride early")
    public void stop(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "player", defaultValue = "", descriptionKey = "command.help.camera.arg.player",
                         description = "Rider to stop; defaults to you") String player) {
        if (GlossCommandMessages.denied(sender, "gloss.camera.stop")) {
            return;
        }
        CameraService camera = plugin.service(CameraService.class);
        if (camera == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_DISABLED);
            return;
        }
        Player rider = player.isBlank()
            ? (sender instanceof Player self ? self : null)
            : Bukkit.getPlayerExact(player);
        if (rider == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_CAMERA_IDLE,
                MessageArgument.untrusted("player", player.isBlank() ? "-" : player));
            return;
        }
        if (!camera.riding(rider.getUniqueId())) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_CAMERA_IDLE,
                MessageArgument.untrusted("player", rider.getName()));
            return;
        }
        camera.stop(rider, CameraService.EndReason.SKIP);
        GlossCommandMessages.send(sender, GlossMessages.WORLD_CAMERA_STOPPED,
            MessageArgument.untrusted("player", rider.getName()));
    }

    /** A slow circle around the sender, looking inward, so an operator can see the ride working. */
    private static List<Spline.Node> demoPath(Location centre, int seconds) {
        int perNode = Math.max(1, seconds * 20 / DEMO_NODES);
        List<Spline.Node> nodes = new ArrayList<>(DEMO_NODES + 1);
        for (int index = 0; index <= DEMO_NODES; index++) {
            double angle = 2.0D * Math.PI * index / DEMO_NODES;
            double x = centre.getX() + Math.cos(angle) * DEMO_RADIUS;
            double z = centre.getZ() + Math.sin(angle) * DEMO_RADIUS;
            float yaw = (float) Math.toDegrees(Math.atan2(centre.getX() - x, z - centre.getZ()));
            nodes.add(new Spline.Node(x, centre.getY() + DEMO_HEIGHT, z, yaw, 20.0F,
                index == DEMO_NODES ? 0 : perNode));
        }
        return nodes;
    }
}
