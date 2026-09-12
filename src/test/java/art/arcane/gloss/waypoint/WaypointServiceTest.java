package art.arcane.gloss.waypoint;

import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.waypoint.AzimuthWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.TrackedWaypoint;
import com.github.retrooper.packetevents.protocol.world.waypoint.Vec3iWaypointInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class WaypointServiceTest {
    private static final UUID VIEWER = UUID.randomUUID();

    @BeforeEach
    void installPacketEvents() {
        PacketEventsStub.install();
    }

    @AfterEach
    void removePacketEvents() {
        PacketEventsStub.uninstall();
    }

    @Test
    void tracksANewWaypointOnce() {
        WaypointTracker tracker = new WaypointTracker();
        List<WaypointTarget> desired = List.of(target("mill", 0xFFAA00, 10, 64, -5));

        Assertions.assertEquals(List.of(WrapperPlayServerWaypoint.Operation.TRACK),
            operations(tracker.reconcile(VIEWER, desired)));
        Assertions.assertEquals(List.of(), tracker.reconcile(VIEWER, desired));
    }

    @Test
    void updatesOnlyWhenTheAnchorMovesMoreThanOneBlock() {
        WaypointTracker tracker = new WaypointTracker();
        tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 10, 64, -5)));

        Assertions.assertEquals(List.of(),
            tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 10.5D, 64, -5))));
        Assertions.assertEquals(List.of(WrapperPlayServerWaypoint.Operation.UPDATE),
            operations(tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 12, 64, -5)))));
    }

    @Test
    void updatesWhenTheColorChanges() {
        WaypointTracker tracker = new WaypointTracker();
        tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 10, 64, -5)));

        Assertions.assertEquals(List.of(WrapperPlayServerWaypoint.Operation.UPDATE),
            operations(tracker.reconcile(VIEWER, List.of(target("mill", 0x00FF00, 10, 64, -5)))));
    }

    @Test
    void untracksAWaypointThatIsNoLongerDesired() {
        WaypointTracker tracker = new WaypointTracker();
        tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 10, 64, -5)));

        List<WaypointTracker.Change> changes = tracker.reconcile(VIEWER, List.of());

        Assertions.assertEquals(List.of(WrapperPlayServerWaypoint.Operation.UNTRACK), operations(changes));
        Assertions.assertEquals("mill", changes.getFirst().id());
    }

    @Test
    void forgettingAViewerEmitsUntracksAndClearsTheState() {
        WaypointTracker tracker = new WaypointTracker();
        tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 10, 64, -5)));

        Assertions.assertEquals(List.of(WrapperPlayServerWaypoint.Operation.UNTRACK),
            operations(tracker.forget(VIEWER)));
        Assertions.assertEquals(List.of(), tracker.forget(VIEWER));
        Assertions.assertEquals(List.of(WrapperPlayServerWaypoint.Operation.TRACK),
            operations(tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 10, 64, -5)))));
    }

    @Test
    void switchingBetweenPositionAndAzimuthUpdates() {
        WaypointTracker tracker = new WaypointTracker();
        tracker.reconcile(VIEWER, List.of(target("mill", 0xFFAA00, 10, 64, -5)));

        WaypointTarget azimuth = new WaypointTarget("mill", 0xFFAA00, WaypointStyle.DEFAULT,
            10, 64, -5, 1.2F);

        Assertions.assertEquals(List.of(WrapperPlayServerWaypoint.Operation.UPDATE),
            operations(tracker.reconcile(VIEWER, List.of(azimuth))));
        Assertions.assertEquals(List.of(), tracker.reconcile(VIEWER, List.of(azimuth)));
    }

    @Test
    void onlyTheNearestWaypointsUpToTheCapAreTracked() {
        List<WaypointTarget> capped = WaypointTracker.capByDistance(List.of(
            distanced("far", 300), distanced("near", 10), distanced("mid", 80)), 0, 0, 0, 2);

        Assertions.assertEquals(List.of("near", "mid"),
            capped.stream().map(WaypointTarget::id).toList());
    }

    @Test
    void theLocatorBarNeedsAtLeastTheVersionThatShipsIt() {
        Assertions.assertFalse(WaypointPackets.supports(null));
        Assertions.assertFalse(WaypointPackets.supports(ClientVersion.V_1_21_5));
        Assertions.assertTrue(WaypointPackets.supports(ClientVersion.V_1_21_6));
        Assertions.assertTrue(WaypointPackets.supports(ClientVersion.V_1_21_11));
    }

    @Test
    void aTrackPacketCarriesTheNamespacedIdentifierAndAPosition() {
        WrapperPlayServerWaypoint packet = WaypointPackets.of(new WaypointTracker.Change(
            WrapperPlayServerWaypoint.Operation.TRACK, target("mill", 0xFFAA00, 10.4D, 64, -5.6D)));

        TrackedWaypoint waypoint = packet.getWaypoint();
        Assertions.assertEquals("gloss:mill", waypoint.getIdentifier().getRight());
        Assertions.assertEquals(0xFFAA00, waypoint.getIcon().getColor().asRGB());
        Assertions.assertInstanceOf(Vec3iWaypointInfo.class, waypoint.getInfo());
        Vec3iWaypointInfo info = (Vec3iWaypointInfo) waypoint.getInfo();
        Assertions.assertEquals(10, info.getPosition().getX());
        Assertions.assertEquals(-6, info.getPosition().getZ());
    }

    @Test
    void anAzimuthTargetBecomesAnAzimuthPacket() {
        WrapperPlayServerWaypoint packet = WaypointPackets.of(new WaypointTracker.Change(
            WrapperPlayServerWaypoint.Operation.TRACK,
            new WaypointTarget("boss", 0x00FF00, WaypointStyle.BOWTIE, 0, 0, 0, 1.5F)));

        Assertions.assertInstanceOf(AzimuthWaypointInfo.class, packet.getWaypoint().getInfo());
        Assertions.assertEquals(1.5F, ((AzimuthWaypointInfo) packet.getWaypoint().getInfo()).getAngle());
    }

    @Test
    void theBowtieStyleUsesTheBowtieIcon() {
        WrapperPlayServerWaypoint packet = WaypointPackets.of(new WaypointTracker.Change(
            WrapperPlayServerWaypoint.Operation.TRACK,
            new WaypointTarget("boss", 0x00FF00, WaypointStyle.BOWTIE, 1, 2, 3, null)));

        Assertions.assertEquals(com.github.retrooper.packetevents.protocol.world.waypoint.WaypointIcon
            .ICON_STYLE_BOWTIE, packet.getWaypoint().getIcon().getStyle());
    }

    private static List<WrapperPlayServerWaypoint.Operation> operations(List<WaypointTracker.Change> changes) {
        return changes.stream().map(WaypointTracker.Change::operation).toList();
    }

    private static WaypointTarget target(String id, int color, double x, double y, double z) {
        return new WaypointTarget(id, color, WaypointStyle.DEFAULT, x, y, z, null);
    }

    private static WaypointTarget distanced(String id, double x) {
        return new WaypointTarget(id, 0xFFFFFF, WaypointStyle.DEFAULT, x, 0, 0, null);
    }
}
