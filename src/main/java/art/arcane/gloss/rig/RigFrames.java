package art.arcane.gloss.rig;

import art.arcane.gloss.animation.clip.ClipSample;
import art.arcane.gloss.util.common.DisplayEntity;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RigFrames {
    private static final byte GLOWING_FLAG = 0x40;

    private RigFrames() {
    }

    public static List<PacketWrapper<?>> transformPackets(List<Part> parts, int[] entityIds, Map<String, Transform> pose,
                                                          int interpolationTicks) {
        return transformPackets(parts, entityIds, pose, interpolationTicks, null);
    }

    /**
     * @param sent the transform last sent per part, updated in place; parts whose transform has
     *     not moved emit nothing. Null sends every part, which is what a first frame wants.
     */
    public static List<PacketWrapper<?>> transformPackets(List<Part> parts, int[] entityIds, Map<String, Transform> pose,
                                                          int interpolationTicks, Map<String, Transform> sent) {
        List<PacketWrapper<?>> packets = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            String partId = parts.get(index).id();
            Transform transform = pose.get(partId);
            if (transform == null) {
                continue;
            }
            if (sent != null && transform.equals(sent.put(partId, transform))) {
                continue;
            }
            packets.add(DisplayEntity.transformUpdate(entityIds[index], transform.translation(), transform.scale(),
                transform.rotation(), Quaternions.identity(), 0, interpolationTicks));
        }
        return packets;
    }

    public static Presentation presentation(ClipSample sample) {
        if (sample == null || sample.isNeutral()) {
            return Presentation.NEUTRAL;
        }
        return new Presentation(sample.glowArgb(), sample.visible(),
            sample.brightness() < 0.0D ? -1 : (int) Math.round(Math.clamp(sample.brightness(), 0.0D, 15.0D)),
            (float) sample.opacity());
    }

    public static WrapperPlayServerEntityMetadata presentationUpdate(int entityId, DisplayEntity display, Presentation next) {
        List<EntityData<?>> metadata = new ArrayList<>(5);
        byte flags = (byte) (display.entityFlags() & ~GLOWING_FLAG);
        int glowOverride = display.glowColorOverride();
        if (next.glowArgb() != 0L) {
            flags |= GLOWING_FLAG;
            glowOverride = (int) (next.glowArgb() & 0xFFFFFFL);
        } else if (glowOverride != -1) {
            flags |= GLOWING_FLAG;
        }
        metadata.add(new EntityData<>(DisplayEntity.MetadataIndex.ENTITY_FLAGS.index(), EntityDataTypes.BYTE, flags));
        metadata.add(new EntityData<>(DisplayEntity.MetadataIndex.GLOW_COLOR_OVERRIDE.index(), EntityDataTypes.INT, glowOverride));
        metadata.add(new EntityData<>(DisplayEntity.MetadataIndex.VIEW_RANGE.index(), EntityDataTypes.FLOAT,
            next.visible() ? display.viewRange() : 0.0F));
        metadata.add(new EntityData<>(DisplayEntity.MetadataIndex.BRIGHTNESS.index(), EntityDataTypes.INT,
            next.brightness() < 0 ? display.brightness() : RigInstance.packedBrightness(next.brightness())));
        if (display.isTextDisplay()) {
            int opacity = Math.round((display.textOpacity() & 0xFF) * Math.clamp(next.opacity(), 0.0F, 1.0F));
            metadata.add(new EntityData<>(DisplayEntity.MetadataIndex.TEXT_OPACITY.index(), EntityDataTypes.BYTE, (byte) opacity));
        }
        return new WrapperPlayServerEntityMetadata(entityId, metadata);
    }

    public record Presentation(long glowArgb, boolean visible, int brightness, float opacity) {
        public static final Presentation NEUTRAL = new Presentation(0L, true, -1, 1.0F);
    }
}
