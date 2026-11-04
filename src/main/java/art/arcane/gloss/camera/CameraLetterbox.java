package art.arcane.gloss.camera;

import art.arcane.gloss.menu.DisplayEntityGroup;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.text.TextDisplayLayout;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.PacketUtils;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The black bars of a camera ride: two text backgrounds that face the camera and ride the carrier,
 * so they hold still on screen without a resource pack. Their screen position follows the client's
 * vertical field of view, which the server cannot read.
 */
final class CameraLetterbox implements CameraService.Letterbox {
    static final float DEPTH = 1F;
    static final float EDGE_TANGENT = 0.6F;
    static final float BAR_WIDTH = 40F;
    static final float BAR_HEIGHT = 8F;
    private static final int CELL_PIXELS_WIDE = 5;
    private static final byte BILLBOARD_CENTER = 3;
    private static final byte SEE_THROUGH = 2;
    private static final int OPAQUE_BLACK = 0xFF000000;

    private final Player viewer;
    private final int carrierId;
    private final int[] barIds;
    private final List<UUID> handles;

    private CameraLetterbox(Player viewer, int carrierId, int[] barIds, List<UUID> handles) {
        this.viewer = viewer;
        this.carrierId = carrierId;
        this.barIds = barIds;
        this.handles = handles;
    }

    static CameraService.Letterbox show(Player viewer, Entity carrier) {
        Location anchor = carrier.getLocation();
        DisplayEntity top = bar(anchor, topBarY());
        DisplayEntity bottom = bar(anchor, bottomBarY());
        DisplayEntityGroup group = DisplayEntityManager.group(viewer, VisibilityGovernor.Surface.SURFACE);
        List<UUID> handles = List.of(DisplayEntityManager.add(group, top), DisplayEntityManager.add(group, bottom));
        group.batch(() -> handles.forEach(handle -> DisplayEntityManager.spawn(handle, viewer)));
        CameraLetterbox letterbox = new CameraLetterbox(viewer, carrier.getEntityId(), new int[]{top.id(), bottom.id()}, handles);
        letterbox.refresh();
        return letterbox;
    }

    static float topBarY() {
        return EDGE_TANGENT * DEPTH;
    }

    static float bottomBarY() {
        return -EDGE_TANGENT * DEPTH - BAR_HEIGHT;
    }

    static float scaleX() {
        return BAR_WIDTH / (CELL_PIXELS_WIDE * TextDisplayLayout.PIXEL_SIZE);
    }

    static float scaleY() {
        return BAR_HEIGHT / (TextDisplayLayout.ROW_PIXELS * TextDisplayLayout.PIXEL_SIZE);
    }

    private static DisplayEntity bar(Location anchor, float y) {
        DisplayEntity display = DisplayEntity.Builder.textDisplay(Component.text(" "), anchor);
        display.billboard(BILLBOARD_CENTER);
        display.textFlags(SEE_THROUGH);
        display.textOpacity((byte) 0);
        display.backgroundColor(OPAQUE_BLACK);
        display.lineWidth(TextDisplayLayout.FULL_WIDTH);
        display.scale(new Vector3f(scaleX(), scaleY(), 1F));
        display.translation(new Vector3f(0F, y, -DEPTH));
        return display;
    }

    @Override
    public void refresh() {
        PacketUtils.sendOne(viewer, new WrapperPlayServerSetPassengers(carrierId, barIds));
    }

    @Override
    public void remove() {
        DisplayEntityManager.deleteAll(handles, viewer);
    }
}
