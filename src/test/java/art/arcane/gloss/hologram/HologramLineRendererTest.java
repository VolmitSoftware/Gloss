package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.packets.PacketEventsStub;
import art.arcane.gloss.util.common.DisplayEntity;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramLineRendererTest {
    @Test
    void chickenRowsUseTheNativeVisualMeshRatherThanItsCollisionHeight() {
        PacketEventsStub.install();
        try {
            EntityLineBounds bounds = EntityLineBounds.forType(EntityTypes.CHICKEN);
            assertEquals(0.9375D, bounds.height());
            assertEquals(0.46975D, bounds.centerY(), 1.0E-9D);
            assertEquals(0.75D, bounds.depth());
            HologramLine source = new HologramLine(HologramLine.Kind.ENTITY, "minecraft:chicken", null,
                ShowCondition.ALWAYS, 1.5D);
            assertEquals(1.40625D, HologramLineRenderer.objectHeight(source));
            assertEquals(8, HologramLineRenderer.rows(source, 1F));
            World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, arguments) -> null);
            Location anchor = new Location(world, 0D, 64D, 0D);
            DisplayEntity display = new DisplayEntity(1, UUID.randomUUID(), EntityTypes.CHICKEN);
            HologramLineRenderer.ObjectLine line = new HologramLineRenderer.ObjectLine(source, display, 2D, 2D);
            for (float yaw : new float[]{0F, 45F}) {
                for (float pitch : new float[]{-45F, 0F, 45F}) {
                    Location eye = new Location(world, 0D, 66D, -8D, yaw, pitch);
                    ParticleFrame frame = TextDisplayStyle.particleFrame(anchor, eye,
                        HologramPresentation.identity(), IconBillboard.CENTER);
                    Vector rowCenter = anchor.toVector().add(frame.up().multiply(line.centerY()));
                    for (double distance : new double[]{4D, 8D, 12D}) {
                        Vector eyePosition = rowCenter.clone().subtract(frame.back().multiply(distance));
                        eye.setX(eyePosition.getX());
                        eye.setY(eyePosition.getY());
                        eye.setZ(eyePosition.getZ());
                        Location feet = HologramLineRenderer.rawPosition(line, frame, eye);
                        double rotation = Math.PI - Math.toRadians(feet.getYaw());
                        Vector right = new Vector(Math.cos(rotation), 0D, -Math.sin(rotation));
                        Vector forward = new Vector(Math.sin(rotation), 0D, Math.cos(rotation));
                        Vector center = feet.toVector().add(new Vector(0D, bounds.centerY() * 1.5D, 0D))
                            .add(forward.clone().multiply(bounds.centerZ() * 1.5D));
                        assertEquals(0D, center.clone().subtract(rowCenter).crossProduct(
                            eyePosition.clone().subtract(rowCenter)).length(), 1.0E-6D);
                        for (int x : new int[]{-1, 1}) {
                            for (int y : new int[]{-1, 1}) {
                                for (int z : new int[]{-1, 1}) {
                                    Vector corner = center.clone().add(right.clone().multiply(x * bounds.width() * 0.75D))
                                        .add(new Vector(0D, y * bounds.height() * 0.75D, 0D))
                                        .add(forward.clone().multiply(z * bounds.depth() * 0.75D));
                                    assertTrue(corner.clone().subtract(rowCenter).dot(frame.back()) <= -0.01999D);
                                    assertTrue(Math.abs(corner.clone().subtract(center).dot(frame.up())) <= line.height() / 2D);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            PacketEventsStub.uninstall();
        }
    }

    @Test
    void nativeClientParagraphHasTenPixelRowsAndNoExtraBottomRow() {
        HologramBoxLayout one = HologramBoxLayout.measure("W", 16384, HologramBox.defaults());
        HologramBoxLayout three = HologramBoxLayout.measure("W\nW\nW", 16384, HologramBox.defaults());
        assertEquals(10, one.textHeight());
        assertEquals(30, three.textHeight());
        assertEquals(7, one.textWidth());
        assertEquals(0.25D, HologramLineRenderer.TEXT_ROW_HEIGHT, 1.0E-7D);
    }

    @Test
    void uprightEntityBoundsStayAheadOfThePlaneAtEveryPitchAndYaw() {
        PacketEventsStub.install();
        NativeEntityDimensionsFixture.install();
        try {
            World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, arguments) -> null);
            Location anchor = new Location(world, 0D, 64D, 0D);
            HologramLine source = new HologramLine(HologramLine.Kind.ENTITY, "minecraft:cow", null,
                ShowCondition.ALWAYS, 0.6D);
            DisplayEntity display = new DisplayEntity(1, UUID.randomUUID(), EntityTypes.COW);
            HologramLineRenderer.ObjectLine line = new HologramLineRenderer.ObjectLine(source, display, 1.5D, 2D);
            double halfHeight = 1.4D * 0.6D / 2D;
            double halfWidth = 0.9D * 0.6D / 2D;
            for (float yaw : new float[]{0F, 45F, 135F, 270F}) {
                for (float pitch : new float[]{-89F, -45F, 0F, 45F, 89F}) {
                    Location eye = new Location(world, 0D, 66D, -12D, yaw, pitch);
                    ParticleFrame frame = TextDisplayStyle.particleFrame(anchor, eye,
                        HologramPresentation.identity(), IconBillboard.CENTER);
                    Location feet = HologramLineRenderer.rawPosition(line, frame, eye);
                    Vector center = feet.toVector().add(new Vector(0D, halfHeight, 0D));
                    double rotation = Math.PI - Math.toRadians(feet.getYaw());
                    Vector right = new Vector(Math.cos(rotation), 0D, -Math.sin(rotation));
                    Vector forward = new Vector(Math.sin(rotation), 0D, Math.cos(rotation));
                    Vector normal = frame.back().multiply(-1D);
                    Vector rowCenter = anchor.toVector().add(frame.up().multiply(line.centerY()));
                    Vector towardEye = eye.toVector().subtract(rowCenter);
                    if (towardEye.dot(normal) > 2D) {
                        assertEquals(0D, center.clone().subtract(rowCenter).crossProduct(towardEye).length(), 1.0E-6D);
                    }
                    for (int x : new int[]{-1, 1}) {
                        for (int y : new int[]{-1, 1}) {
                            for (int z : new int[]{-1, 1}) {
                                Vector corner = center.clone().add(right.clone().multiply(x * halfWidth))
                                    .add(new Vector(0D, y * halfHeight, 0D))
                                    .add(forward.clone().multiply(z * halfWidth));
                                assertTrue(corner.clone().subtract(rowCenter).dot(normal) >= 0.01999D);
                                assertTrue(Math.abs(corner.clone().subtract(center).dot(frame.up()))
                                    <= HologramLineRenderer.rows(source, 1F) * 0.25D / 2D);
                            }
                        }
                    }
                    assertEquals(0F, feet.getPitch());
                }
            }
        } finally {
            PacketEventsStub.uninstall();
        }
    }

    @Test
    void itemRowsStayInTheBillboardTransformAheadOfTheBackgroundPlane() {
        PacketEventsStub.install();
        try {
            Location anchor = new Location(null, 4D, 64D, 8D, 30F, 15F);
            IconDisplayStyle style = IconDisplayStyle.hologramDefaults().withScale(1F, 1.5F, 1F);
            DisplayEntity rendered = new DisplayEntity(1, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY)
                .displayKind(DisplayEntity.DisplayKind.ITEM).location(new Vector3d(4D, 64D, 8D));
            double rowCenter = 0.25D * 1.5D * 2D;
            HologramLineRenderer.configureObjectLayout(rendered, 0.6F, rowCenter, style);

            assertEquals(anchor.getY(), rendered.location().getY());
            assertEquals(style.billboard().metadataValue(), rendered.billboard());
            assertEquals(rowCenter, rendered.translation().getY(), 1.0E-6D);
            assertTrue(rendered.translation().getZ() - 0.6D / 2D > 0D);
        } finally {
            PacketEventsStub.uninstall();
        }
    }

    @Test
    void mixedObjectsOccupyTheirNativeBottomAnchoredTextRowsAcrossScales() {
        PacketEventsStub.install();
        NativeEntityDimensionsFixture.install();
        try {
            HologramLine upper = new HologramLine(HologramLine.Kind.ENTITY, "minecraft:cow", null,
                ShowCondition.ALWAYS, 0.6D);
            HologramLine lower = new HologramLine(HologramLine.Kind.ENTITY, "minecraft:pig", null,
                ShowCondition.ALWAYS, 1.0D);
            List<HologramLine> authored = List.of(HologramLine.text("Title"), upper,
                HologramLine.text("Middle\nDetail"), lower);
            Location anchor = new Location(null, 4D, 64D, 8D);
            for (float scaleY : new float[]{0.5F, 1F, 1.8F, 3F}) {
                List<HologramLineRenderer.ObjectLine> objects = HologramLineRenderer.build(null, "mixed",
                    authored, anchor, IconDisplayStyle.hologramDefaults().withScale(1F, scaleY, 1F));
                int upperRows = HologramLineRenderer.rows(upper, scaleY);
                int lowerRows = HologramLineRenderer.rows(lower, scaleY);
                String nativeText = "Title\n" + " \n".repeat(upperRows) + "Middle\nDetail\n"
                    + " \n".repeat(lowerRows - 1) + " ";
                HologramBoxLayout bounds = HologramBoxLayout.measure(nativeText, 16384, HologramBox.defaults());
                double pixel = 0.025D * scaleY;
                double textTop = anchor.getY() + bounds.textHeight() * pixel;
                double upperHeight = HologramLineRenderer.objectHeight(upper);
                double lowerHeight = HologramLineRenderer.objectHeight(lower);
                double upperCenter = objects.get(0).display().location().getY() + upperHeight / 2D;
                double lowerCenter = objects.get(1).display().location().getY() + lowerHeight / 2D;
                double rowHeight = 10D * pixel;

                assertEquals(textTop - rowHeight * (1D + upperRows / 2D), upperCenter, 1.0E-6D);
                assertEquals(anchor.getY() + lowerRows * rowHeight / 2D, lowerCenter, 1.0E-6D);
                assertTrue(upperCenter - upperHeight / 2D >= textTop - rowHeight * (1 + upperRows) - 1.0E-6D);
                assertTrue(upperCenter + upperHeight / 2D <= textTop - rowHeight + 1.0E-6D);
                assertTrue(lowerCenter - lowerHeight / 2D >= anchor.getY() - 1.0E-6D);
                assertTrue(lowerCenter + lowerHeight / 2D <= textTop - rowHeight * (3 + upperRows) + 1.0E-6D);
                assertEquals(upperRows * rowHeight, objects.get(0).height(), 1.0E-6D);
                assertEquals(lowerRows * rowHeight, objects.get(1).height(), 1.0E-6D);
            }
        } finally {
            PacketEventsStub.uninstall();
        }
    }
}
