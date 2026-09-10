package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.api.IconDisplayStyle;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class TextDisplayStyle {
    private TextDisplayStyle() {
    }

    public static void apply(TextDisplay display, IconDisplayStyle style) {
        display.setBillboard(switch (style.billboard()) {
            case FIXED -> Display.Billboard.FIXED;
            case VERTICAL -> Display.Billboard.VERTICAL;
            case HORIZONTAL -> Display.Billboard.HORIZONTAL;
            case CENTER -> Display.Billboard.CENTER;
        });
        display.setAlignment(switch (style.textAlignment()) {
            case LEFT -> TextDisplay.TextAlignment.LEFT;
            case RIGHT -> TextDisplay.TextAlignment.RIGHT;
            case CENTER -> TextDisplay.TextAlignment.CENTER;
        });
        display.setShadowed(style.shadow());
        display.setSeeThrough(style.seeThrough());
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(style.backgroundArgb().argb()));
        display.setLineWidth(style.lineWidth());
        display.setBrightness(style.blockLight() == null ? null
            : new Display.Brightness(style.blockLight(), style.skyLight()));
        display.setViewRange(style.viewRange());
        display.setShadowRadius(style.shadowRadius());
        display.setShadowStrength(style.shadowStrength());
        display.setDisplayWidth(style.cullingWidth());
        display.setDisplayHeight(style.cullingHeight());
        display.setGlowing(style.glowColor() != null);
        display.setGlowColorOverride(style.glowColor() == null ? null
            : Color.fromRGB(style.glowColor().argb() & 0xFFFFFF));
    }

    public static void apply(DisplayEntity display, IconDisplayStyle style) {
        display.entityFlags((byte) (display.entityFlags() | style.entityFlags()));
        display.billboard(style.billboard().metadataValue());
        display.textFlags(style.textFlags());
        display.backgroundColor(style.backgroundArgb().argb());
        display.textOpacity((byte) (style.textOpacity() & 0xFF));
        display.lineWidth(style.lineWidth());
        display.brightness(style.packedBrightness());
        display.viewRange(style.viewRange());
        display.shadowRadius(style.shadowRadius());
        display.shadowStrength(style.shadowStrength());
        display.width(style.cullingWidth());
        display.height(style.cullingHeight());
        display.glowColorOverride(style.glowColorOverride());
    }

    public static Vector3f scale(HologramPresentation presentation, IconDisplayStyle style) {
        return new Vector3f((float) presentation.scaleX() * (style == null ? 1F : style.scaleX()),
            (float) presentation.scaleY() * (style == null ? 1F : style.scaleY()),
            (float) presentation.scaleZ() * (style == null ? 1F : style.scaleZ()));
    }

    public static Quaternionf rotation(HologramPresentation presentation) {
        return new Quaternionf().rotationXYZ(
            (float) Math.toRadians(presentation.rotationXDegrees()),
            (float) Math.toRadians(presentation.rotationYDegrees()),
            (float) Math.toRadians(presentation.rotationZDegrees()));
    }

    public static Transformation transform(HologramPresentation presentation, IconDisplayStyle style) {
        return new Transformation(new Vector3f(), rotation(presentation), scale(presentation, style),
            new Quaternionf());
    }

    public static byte opacity(HologramPresentation presentation, IconDisplayStyle style) {
        return (byte) Math.round(presentation.opacity() * (style == null ? 255 : style.textOpacity()));
    }
    public static ParticleFrame particleFrame(Location anchor, Location eye, HologramPresentation presentation,
                                       IconBillboard billboard) {
        float yaw = switch (billboard) {
            case FIXED, HORIZONTAL -> -anchor.getYaw();
            case VERTICAL, CENTER -> 180F - eye.getYaw();
        };
        float pitch = switch (billboard) {
            case FIXED, VERTICAL -> anchor.getPitch();
            case HORIZONTAL, CENTER -> -eye.getPitch();
        };
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.toRadians(yaw),
            (float) Math.toRadians(pitch), 0F).mul(TextDisplayStyle.rotation(presentation));
        return new ParticleFrame(anchor, rotateAxis(rotation, new Vector3f(1F, 0F, 0F)),
            rotateAxis(rotation, new Vector3f(0F, 1F, 0F)),
            rotateAxis(rotation, new Vector3f(0F, 0F, -1F)));
    }

    private static Vector rotateAxis(Quaternionf rotation, Vector3f axis) {
        rotation.transform(axis);
        return new Vector(axis.x, axis.y, axis.z);
    }

}
