package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TextDisplayDecoration {
    private final HologramService service;
    private final Runnable visibilityChanged;
    private final AtomicBoolean spawning = new AtomicBoolean();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final Map<UUID, Player> visibleViewers = new ConcurrentHashMap<>();
    private volatile List<TextDisplay> displays = List.of();
    private volatile State state;

    public TextDisplayDecoration(HologramService service, Runnable visibilityChanged) {
        this.service = service;
        this.visibilityChanged = visibilityChanged;
    }

    public List<TextDisplay> displays() {
        return displays;
    }

    public void update(Update update) {
        if (destroyed.get()) {
            return;
        }
        Location anchor = update.anchor();
        HologramPresentation presentation = update.presentation();
        IconDisplayStyle style = update.style();
        HologramBox box = update.box();
        String text = update.text();
        int teleportTicks = update.teleportTicks();
        State previous = state;
        if (previous != null && previous.anchor().equals(anchor) && previous.presentation().equals(presentation)
            && Objects.equals(previous.style(), style) && previous.box().equals(box)
            && previous.text().equals(text) && previous.teleportTicks() == teleportTicks) {
            return;
        }
        int lineWidth = style == null ? 16384 : style.lineWidth();
        int previousLineWidth = previous == null || previous.style() == null ? 16384 : previous.style().lineWidth();
        boolean sameLayout = previous != null && previous.text().equals(text) && previousLineWidth == lineWidth
            && previous.box().padding().equals(box.padding()) && previous.box().borderWidth().equals(box.borderWidth());
        HologramBoxLayout layout = sameLayout ? previous.layout() : HologramBoxLayout.measure(text, lineWidth, box);
        List<HologramBoxLayout.Part> parts = sameLayout && previous.box().equals(box)
            ? previous.parts() : layout.parts(box);
        State next = new State(anchor.clone(), presentation, style, box,
            text, layout, parts, teleportTicks);
        if (previous != null && !sameTopology(previous, next)) {
            removeDisplays(anchor);
        }
        List<TextDisplay> current = displays;
        if (next.parts().isEmpty()) {
            state = next;
            return;
        }
        if (current.isEmpty()) {
            spawn(next);
            return;
        }
        state = next;
        boolean appearanceChanged = previous == null || !previous.presentation().equals(next.presentation())
            || !Objects.equals(previous.style(), next.style())
            || !previous.box().equals(next.box()) || !previous.layout().equals(next.layout())
            || previous.teleportTicks() != next.teleportTicks();
        for (int index = 0; index < current.size(); index++) {
            TextDisplay display = current.get(index);
            HologramBoxLayout.Part part = next.parts().get(index);
            boolean moved = previous == null || !previous.anchor().equals(next.anchor());
            if (moved) {
                service.plugin().scheduler().teleport(display, next.anchor().clone());
            }
            if (!appearanceChanged) {
                continue;
            }
            Runnable apply = () -> {
                if (!destroyed.get() && state == next && display.isValid()) {
                    configure(display, next, part);
                }
            };
            if (FoliaScheduler.isOwnedByCurrentRegion(display)) {
                apply.run();
            } else {
                service.plugin().scheduler().runEntity(display, apply);
            }
        }
    }

    public void destroy(Location anchor) {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        visibleViewers.clear();
        removeDisplays(anchor);
    }

    public void setVisible(Player viewer, boolean visible) {
        if (destroyed.get()) {
            return;
        }
        UUID viewerId = viewer.getUniqueId();
        Player previous = visible ? visibleViewers.put(viewerId, viewer) : visibleViewers.remove(viewerId);
        if (visible && previous == viewer) {
            return;
        }
        applyVisibility(viewer);
    }

    private void applyVisibility(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        FoliaScheduler.runEntity(service.plugin(), viewer, () -> {
            if (destroyed.get() || !viewer.isOnline()) {
                return;
            }
            boolean visible = visibleViewers.get(viewerId) == viewer;
            for (TextDisplay display : displays) {
                if (visible) {
                    viewer.showEntity(service.plugin(), display);
                } else {
                    viewer.hideEntity(service.plugin(), display);
                }
            }
        });
    }

    private static boolean sameTopology(State previous, State next) {
        return previous.parts().size() == next.parts().size()
            && (previous.box().backgroundArgb().argb() >>> 24 == 0)
            == (next.box().backgroundArgb().argb() >>> 24 == 0);
    }

    private void removeDisplays(Location anchor) {
        List<TextDisplay> current;
        synchronized (this) {
            current = displays;
            displays = List.of();
        }
        for (TextDisplay display : current) {
            service.despawnEntity(display, anchor);
        }
    }

    private void spawn(State next) {
        if (!spawning.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = service.plugin().scheduler().runAt(next.anchor(), () -> {
            List<TextDisplay> created = new ArrayList<>(next.parts().size());
            try {
                World world = next.anchor().getWorld();
                if (destroyed.get() || !world.isChunkLoaded(next.anchor().getBlockX() >> 4,
                    next.anchor().getBlockZ() >> 4)) {
                    return;
                }
                for (HologramBoxLayout.Part part : next.parts()) {
                    created.add(world.spawn(next.anchor(), TextDisplay.class, display -> {
                        service.configureDisplay(display, false, Display.Billboard.CENTER);
                        display.setText(" ");
                        display.setTextOpacity((byte) 0);
                        DisplayVisibility.setVisibleByDefault(display, false);
                        configure(display, next, part);
                    }));
                }
                synchronized (this) {
                    if (destroyed.get()) {
                        return;
                    }
                    displays = List.copyOf(created);
                    state = next;
                    created.clear();
                }
                for (Player viewer : visibleViewers.values()) {
                    applyVisibility(viewer);
                }
                visibilityChanged.run();
            } catch (RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "text-display-box-spawn", failure,
                    "Failed to create a text display box.");
            } finally {
                for (TextDisplay display : created) {
                    service.despawnEntity(display, next.anchor());
                }
                spawning.set(false);
            }
        });
        if (!scheduled) {
            spawning.set(false);
        }
    }

    private static void configure(TextDisplay display, State state, HologramBoxLayout.Part part) {
        if (state.style() != null) {
            TextDisplayStyle.apply(display, state.style());
        }
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setLineWidth(16384);
        display.setDefaultBackground(false);
        int color = part.color();
        double opacity = state.presentation().opacity() * (state.style() == null ? 1D : state.style().textOpacity() / 255D);
        int alpha = (int) Math.round((color >>> 24) * opacity);
        display.setBackgroundColor(Color.fromARGB(alpha << 24 | color & 0xFFFFFF));
        Transformation transform = state.layout().transform(part, state.presentation(), state.style());
        display.setInterpolationDuration(state.teleportTicks());
        if (state.teleportTicks() > 0) {
            DisplayMotion.applyTeleportDuration(display, state.teleportTicks());
            display.setInterpolationDelay(-1);
        }
        display.setTransformation(transform);
    }

    public record Update(Location anchor, HologramPresentation presentation, IconDisplayStyle style,
                         HologramBox box, String text, int teleportTicks) {
        public Update {
            anchor = Objects.requireNonNull(anchor).clone();
            presentation = Objects.requireNonNull(presentation);
            box = Objects.requireNonNull(box);
            text = Objects.requireNonNull(text);
        }

        @Override
        public Location anchor() {
            return anchor.clone();
        }
    }

    private record State(Location anchor, HologramPresentation presentation, IconDisplayStyle style,
                         HologramBox box, String text, HologramBoxLayout layout, List<HologramBoxLayout.Part> parts,
                         int teleportTicks) {
    }
}
