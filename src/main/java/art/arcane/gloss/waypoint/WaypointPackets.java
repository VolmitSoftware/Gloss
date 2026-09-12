package art.arcane.gloss.waypoint;

import com.github.retrooper.packetevents.protocol.color.Color;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.waypoint.AzimuthWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.TrackedWaypoint;
import com.github.retrooper.packetevents.protocol.world.waypoint.Vec3iWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.WaypointIcon;
import com.github.retrooper.packetevents.protocol.world.waypoint.WaypointInfo;
import com.github.retrooper.packetevents.util.Either;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;

/**
 * Builds the locator-bar packets. Identifiers are namespaced {@code gloss:<id>} so another
 * plugin's waypoints and Gloss's never collide in the client's tracked set.
 */
public final class WaypointPackets {
    public static final String NAMESPACE = "gloss:";
    /** The protocol version that introduced the locator bar. */
    public static final ClientVersion MINIMUM_CLIENT = ClientVersion.V_1_21_6;

    private WaypointPackets() {
    }

    public static boolean supports(ClientVersion version) {
        return version != null && version.isNewerThanOrEquals(MINIMUM_CLIENT);
    }

    public static WrapperPlayServerWaypoint of(WaypointTracker.Change change) {
        return new WrapperPlayServerWaypoint(change.operation(), waypoint(change.target()));
    }

    private static TrackedWaypoint waypoint(WaypointTarget target) {
        return new TrackedWaypoint(Either.createRight(NAMESPACE + target.id()), icon(target), info(target));
    }

    private static WaypointIcon icon(WaypointTarget target) {
        return new WaypointIcon(
            target.style() == WaypointStyle.BOWTIE ? WaypointIcon.ICON_STYLE_BOWTIE : WaypointIcon.ICON_STYLE_DEFAULT,
            new Color(target.color()));
    }

    private static WaypointInfo info(WaypointTarget target) {
        if (target.azimuth()) {
            return new AzimuthWaypointInfo(target.azimuthRadians());
        }
        return new Vec3iWaypointInfo(new Vector3i(
            (int) Math.floor(target.x()), (int) Math.floor(target.y()), (int) Math.floor(target.z())));
    }
}
