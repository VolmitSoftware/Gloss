package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.util.common.DisplayEntity;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Quaternion4f;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PacketTextDecoration {
    private final Player viewer;
    private final List<UUID> displays = new ArrayList<>(5);
    private Update previous;
    private HologramBoxLayout measured;

    public PacketTextDecoration(Player viewer) {
        this.viewer = Objects.requireNonNull(viewer);
    }

    public void update(Update update) {
        if (!update.box().enabled()) {
            remove();
            return;
        }
        Update last = previous;
        if (update.equals(last)) {
            return;
        }
        boolean measure = last == null || !last.text().equals(update.text())
            || !last.style().lineWidth().equals(update.style().lineWidth())
            || !last.box().padding().equals(update.box().padding())
            || !last.box().borderWidth().equals(update.box().borderWidth());
        HologramBoxLayout layout = measure
            ? HologramBoxLayout.measure(update.text(), update.style().lineWidth(), update.box()) : measured;
        List<HologramBoxLayout.Part> parts = layout.parts(update.box());
        boolean appearanceChanged = last == null || !last.style().equals(update.style())
            || !last.box().equals(update.box()) || parts.size() != displays.size();
        boolean geometryChanged = appearanceChanged || measure || !last.presentation().equals(update.presentation())
            || !last.translation().equals(update.translation());
        boolean moved = last == null || !last.anchor().equals(update.anchor());
        Quaternionf rotation = TextDisplayStyle.rotation(update.presentation());
        Quaternion4f orientation = new Quaternion4f(rotation.x, rotation.y, rotation.z, rotation.w);
        if (appearanceChanged) {
            remove();
        }
        for (int index = 0; index < parts.size(); index++) {
            HologramBoxLayout.Part part = parts.get(index);
            Transformation transform = geometryChanged ? layout.transform(part, update.presentation(), update.style()) : null;
            Vector3f positioned = transform == null ? null
                : new Vector3f(transform.getTranslation().x + update.translation().getX(),
                    transform.getTranslation().y + update.translation().getY(), transform.getTranslation().z + update.translation().getZ());
            Vector3f scale = transform == null ? null
                : new Vector3f(transform.getScale().x, transform.getScale().y, transform.getScale().z);
            if (appearanceChanged) {
                DisplayEntity display = DisplayEntity.Builder.textDisplay(Component.text(" "), update.anchor());
                TextDisplayStyle.apply(display, update.style());
                display.textFlags((byte) (update.style().textFlags() & 0x03));
                display.textOpacity((byte) 0);
                double opacity = update.presentation().opacity() * update.style().textOpacity() / 255D;
                int alpha = (int) Math.round((part.color() >>> 24) * opacity);
                display.backgroundColor(alpha << 24 | part.color() & 0xFFFFFF);
                display.lineWidth(16384);
                display.scale(new Vector3f(scale.getX(), scale.getY(), scale.getZ()));
                display.translation(positioned);
                UUID id = DisplayEntityManager.add(display);
                displays.add(id);
                DisplayEntityManager.orient(id, update.anchor().getYaw(), update.anchor().getPitch(),
                    orientation);
                DisplayEntityManager.spawn(id, viewer);
            } else {
                UUID id = displays.get(index);
                if (moved) {
                    DisplayEntityManager.goTo(id, update.anchor());
                }
                if (geometryChanged) {
                    DisplayEntityManager.changeTransform(id, scale.getX(), scale.getY(), scale.getZ(), positioned);
                }
                if (last.presentation().opacity() != update.presentation().opacity()) {
                    double opacity = update.presentation().opacity() * update.style().textOpacity() / 255D;
                    int alpha = (int) Math.round((part.color() >>> 24) * opacity);
                    DisplayEntityManager.changeTextBackground(id, alpha << 24 | part.color() & 0xFFFFFF);
                }
                DisplayEntityManager.orient(id, update.anchor().getYaw(), update.anchor().getPitch(),
                    orientation);
            }
        }
        previous = update;
        measured = layout;
    }

    public void remove() {
        DisplayEntityManager.deleteAll(displays, viewer);
        displays.clear();
        previous = null;
        measured = null;
    }

    public record Update(Location anchor, String text, IconDisplayStyle style, HologramBox box,
                         HologramPresentation presentation, Vector3f translation) {
        public static Update atAnchor(Location anchor, String text, IconDisplayStyle style,
                                      HologramBox box, HologramPresentation presentation) {
            return new Update(anchor, text, style, box, presentation, new Vector3f(0F, 0F, 0F));
        }

        public Update {
            anchor = Objects.requireNonNull(anchor).clone();
            text = Objects.requireNonNull(text);
            style = Objects.requireNonNull(style);
            box = Objects.requireNonNull(box);
            presentation = Objects.requireNonNull(presentation);
            translation = new Vector3f(translation.getX(), translation.getY(), translation.getZ());
        }
    }
}
