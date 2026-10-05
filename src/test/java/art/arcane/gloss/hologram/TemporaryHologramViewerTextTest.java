package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.menu.DisplayEntityManagerPacketShapeTest;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.TextUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryHologramViewerTextTest {
    @TempDir
    File directory;

    @BeforeEach
    void installPackets() {
        DisplayEntityManagerPacketShapeTest.installPacketEventsApi();
    }

    @AfterEach
    void clearPackets() {
        new DisplayEntityManagerPacketShapeTest().clearSent();
        DisplayEntityManagerPacketShapeTest.clearPacketEventsApi();
    }

    @Test
    void renderedViewerBindingsPreserveLiteralNamesAndCanBeReplacedOrCleared() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            harness.configure(config -> config.holograms.perViewerPlaceholders = false);
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alice", world, 1, 64, 1);
            PlayerHandle bob = harness.join("Bob", world, 2, 64, 1);
            TemporaryHologramDisplay hologram = harness.temporary("bound", harness.at(world, 0, 64, 0), 60000L);
            hologram.setRenderedLines(List.of("Fallback"));
            hologram.bindRenderedViewerText(player -> new TemporaryHologram.RenderedText("§a" + player.getName()
                + "\n<red>{{ player.name }}</red> |animation.fast| %player_name%", List.of(), null));
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(2, harness.animator.pass(0L));
            assertEquals("Alice\n<red>{{ player.name }}</red> |animation.fast| %player_name%",
                receivedText(harness, alice));
            assertEquals("Bob\n<red>{{ player.name }}</red> |animation.fast| %player_name%",
                receivedText(harness, bob));
            assertTrue(harness.sender.sent.stream().allMatch(sent -> sent.codec() == TextCodec.LEGACY));

            hologram.bindRenderedViewerText(player -> new TemporaryHologram.RenderedText("Changed " + player.getName(), List.of(), null));
            hologram.drive(true);
            assertEquals(2, harness.animator.pass(1L));
            assertEquals("Changed Alice", receivedText(harness, alice));

            hologram.bindRenderedViewerText(null);
            hologram.drive(true);
            assertEquals("Fallback", harness.onlySpawned(world).lastText());
            assertEquals(0, harness.animator.pendingTextUpdateCount());
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
        }
    }

    @Test
    void queuedViewerBindingReplacementCannotPublishStaleNames() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay hologram = harness.temporary("replace", harness.at(world, 0, 64, 0), 60000L);
            hologram.bindRenderedViewerText(player -> {
                hologram.bindRenderedViewerText(next -> new TemporaryHologram.RenderedText("Current", List.of(), null));
                return new TemporaryHologram.RenderedText("Stale", List.of(), null);
            });
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(0, harness.animator.pass(0L));
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(1L));
            assertEquals("Current", receivedText(harness, viewer));
            hologram.bindRenderedFrames(now -> List.of("Shared"));
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(2L));
            assertEquals("Shared", receivedText(harness, viewer));
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
        }
    }

    @Test
    void queuedViewerBindingDoesNotRunAfterDestruction() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Viewer", world, 0, 64, 0);
            AtomicBoolean called = new AtomicBoolean();
            TemporaryHologramDisplay hologram = harness.temporary("destroy-bound", harness.at(world, 0, 64, 0), 60000L);
            hologram.bindRenderedViewerText(player -> {
                called.set(true);
                return new TemporaryHologram.RenderedText("Removed", List.of(), null);
            });
            hologram.drive(true);
            called.set(false);
            harness.ownsThread = false;
            harness.deferImmediateTasks = true;
            hologram.drive(true);
            hologram.destroy();
            harness.drainImmediate();
            harness.drainImmediate();
            assertFalse(called.get());
            assertEquals(0, harness.animator.pass(0L));
            assertTrue(harness.liveSpawned(world).isEmpty());
        }
    }

    @Test
    void changingTextCodecResendsIdenticalViewerText() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay hologram = harness.temporary("codec", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("{{ player.name }}"));
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(0L));
            assertEquals(TextCodec.AUTHORED, harness.sender.sent.getLast().codec());
            hologram.bindRenderedViewerText(player -> new TemporaryHologram.RenderedText(player.getName(), List.of(), null));
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(1L));
            assertEquals(TextCodec.LEGACY, harness.sender.sent.getLast().codec());
            hologram.bindRenderedViewerText(null);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(2L));
            assertEquals(TextCodec.AUTHORED, harness.sender.sent.getLast().codec());
        }
    }

    @Test
    void boundAnimationFramesAdvanceWithoutResamplingViewerState() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            AtomicBoolean sampled = new AtomicBoolean();
            TemporaryHologramDisplay hologram = harness.temporary("bound-animation", harness.at(world, 0, 64, 0), 60000L);
            hologram.bindRenderedViewerText(player -> {
                assertFalse(sampled.getAndSet(true));
                String name = player.getName();
                return new TemporaryHologram.RenderedText(name, List.of(),
                    now -> name + " <red>" + now + "</red>");
            });
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(0L));
            assertEquals("Viewer <red>0</red>", receivedText(harness, viewer));
            assertEquals(1, harness.animator.pass(10L));
            assertEquals("Viewer <red>10</red>", receivedText(harness, viewer));
            assertEquals(TextCodec.LEGACY, harness.sender.sent.getLast().codec());
            hologram.destroy();
            assertEquals(0, harness.animator.targetCount());
        }
    }

    @Test
    void viewersReceiveTheirOwnTextAndBoxSizesWithoutExtraWorldEntities() throws ReflectiveOperationException {
        int existing = DisplayEntityManager.totalCount();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alexandria", world, 1, 64, 1);
            PlayerHandle bob = harness.join("Bob", world, 2, 64, 1);
            TemporaryHologramDisplay hologram = harness.temporary("names", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("<gold>{{ player.name }}</gold>"));
            hologram.setBox(new HologramBox(true, 4, 1, null, null));
            hologram.drive(true);
            hologram.drive(true);

            assertEquals(2, harness.animator.pass(0L));
            assertEquals("Alexandria", receivedText(harness, alice));
            assertEquals("Bob", receivedText(harness, bob));
            assertEquals("", harness.onlySpawned(world).lastText());
            assertEquals(existing + 10, DisplayEntityManager.totalCount());
            assertTrue(maximumWidth(alice.proxy) > maximumWidth(bob.proxy));

            hologram.teleport(harness.at(world, 3, 65, 4));
            hologram.bindPresentation(alice.proxy, () -> new HologramPresentation(1D, 1D, 1D, 12D, 23D, 34D, 1D));
            hologram.scheduleDrive(new HologramTick(), true);
            for (Map.Entry<UUID, Player> visible : visiblePackets().entrySet()) {
                if (visible.getValue() != alice.proxy && visible.getValue() != bob.proxy) {
                    continue;
                }
                DisplayEntity entity = packetDisplays().get(visible.getKey());
                assertEquals(3D, entity.location().getX());
                assertEquals(65D, entity.location().getY());
                assertEquals(4D, entity.location().getZ());
                assertTrue(Math.abs(entity.leftRotation().getX()) > 0.01F);
                assertTrue(Math.abs(entity.leftRotation().getY()) > 0.01F);
            }
            hologram.destroy();
            assertTrue(harness.liveSpawned(world).isEmpty());
            assertEquals(existing, DisplayEntityManager.totalCount());
            assertEquals(0, harness.animator.pendingTextUpdateCount());
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
        }
    }

    @Test
    void hiddenViewerTextAndBoxesRetireAndReturnWithTheCondition() {
        int existing = DisplayEntityManager.totalCount();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            AtomicBoolean visible = new AtomicBoolean(true);
            TemporaryHologramDisplay hologram = harness.temporary("show", harness.at(world, 0, 64, 0), 60000L);
            hologram.setViewerCondition(player -> visible.get());
            hologram.setLines(List.of("{{ player.name }}"));
            hologram.setBox(new HologramBox(true, 4, 1, null, null));
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(0L));
            assertEquals(existing + 5, DisplayEntityManager.totalCount());

            visible.set(false);
            hologram.drive(true);
            assertEquals(0, harness.animator.pass(100L));
            assertEquals(existing, DisplayEntityManager.totalCount());
            visible.set(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(200L));
            assertEquals("Viewer", receivedText(harness, viewer));
            assertEquals(existing + 5, DisplayEntityManager.totalCount());
            hologram.destroy();
            assertEquals(existing, DisplayEntityManager.totalCount());
        }
    }

    @Test
    void queuedViewerRenderingCannotPublishAfterDestruction() {
        int existing = DisplayEntityManager.totalCount();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay hologram = harness.temporary("retire", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("{{ player.name }}"));
            hologram.setBox(new HologramBox(true, 4, 1, null, null));
            hologram.drive(true);
            harness.deferImmediateTasks = true;
            hologram.drive(true);
            hologram.destroy();
            harness.drainImmediate();
            harness.drainImmediate();
            assertEquals(0, harness.animator.pass(0L));
            assertEquals(existing, DisplayEntityManager.totalCount());
            assertTrue(harness.liveSpawned(world).isEmpty());
        }
    }

    @Test
    void animatedViewerTextKeepsSpansAndReturnsToSafeRenderedLines() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay hologram = harness.temporary("frames", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("<particles:caption>{{ player.name }} |animation.fast|</particles>"));
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(0L));
            assertEquals("Viewer A", receivedText(harness, viewer));
            assertEquals(1, harness.animator.pass(10L));
            assertEquals("Viewer B", receivedText(harness, viewer));

            String literal = "<red>{{ player.name }}</red> |animation.fast|";
            hologram.setRenderedLines(List.of(literal));
            hologram.drive(true);
            assertEquals(literal, harness.onlySpawned(world).lastText());
            assertEquals(0, harness.animator.targetCount());
            assertEquals(0, harness.animator.pendingTextUpdateCount());
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
        }
    }

    @Test
    void trackingRestartsResendConstantPersonalizedTextAndRebuildBoxes() {
        int existing = DisplayEntityManager.totalCount();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay hologram = harness.temporary("tracking", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("{{ player.name }}"));
            hologram.setBox(new HologramBox(true, 4, 1, null, null));
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(0L));
            int entityId = harness.onlySpawned(world).proxy.getEntityId();
            harness.service.displayTrackingChanged(entityId, viewer.proxy, viewer.uuid, true);
            harness.drainDelayed();
            assertEquals(existing, DisplayEntityManager.totalCount());
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(100L));
            assertEquals(existing + 5, DisplayEntityManager.totalCount());

            harness.service.displayTrackingChanged(entityId, viewer.proxy, viewer.uuid, false);
            harness.drainDelayed();
            hologram.drive(true);
            assertEquals(0, harness.animator.pass(200L));
            assertEquals(existing, DisplayEntityManager.totalCount());
            harness.service.displayTrackingChanged(entityId, viewer.proxy, viewer.uuid, true);
            harness.drainDelayed();
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(300L));
            assertEquals("Viewer", receivedText(harness, viewer));
            assertEquals(existing + 5, DisplayEntityManager.totalCount());
            hologram.destroy();
            harness.service.displayTrackingChanged(entityId, viewer.proxy, viewer.uuid, true);
            harness.drainDelayed();
            assertEquals(0, harness.animator.pass(400L));
            assertEquals(existing, DisplayEntityManager.totalCount());
        }
    }

    @Test
    void respawnInvalidatesTextAndPacketBoxesEvenWhenVisibilityDidNotChange() {
        int existing = DisplayEntityManager.totalCount();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay hologram = harness.temporary("respawn", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("{{ player.name }}"));
            hologram.setBox(new HologramBox(true, 4, 1, null, null));
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(0L));
            assertEquals(existing + 5, DisplayEntityManager.totalCount());
            harness.fireRespawn(viewer, harness.at(world, 0, 64, 0));
            assertEquals(existing, DisplayEntityManager.totalCount());
            hologram.drive(true);
            assertEquals(1, harness.animator.pass(100L));
            assertEquals(existing + 5, DisplayEntityManager.totalCount());
            hologram.destroy();
        }
    }

    @Test
    void oneViewersConditionChangeDoesNotRetractOtherViewersAnimation() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alice", world, 0, 64, 0);
            PlayerHandle bob = harness.join("Bob", world, 0, 64, 0);
            AtomicBoolean visible = new AtomicBoolean(true);
            TemporaryHologramDisplay hologram = harness.temporary("audience", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("{{ player.name }} |animation.fast|"));
            hologram.setViewerCondition(player -> player == bob.proxy || visible.get());
            hologram.drive(true);
            hologram.drive(true);
            assertEquals(2, harness.animator.targetCount());
            assertEquals(2, harness.animator.pass(0L));
            visible.set(false);
            harness.onlySpawned(world).entityIdRead = () -> {
                throw new AssertionError("Visibility work read the display ID from the viewer owner");
            };
            hologram.reconcileVisibilityFor(alice.proxy);
            assertTrue(harness.schedulerErrors.isEmpty(), harness.schedulerErrors.toString());
            assertEquals(1, harness.animator.targetCount());
            assertEquals(1, harness.animator.pass(10L));
            assertEquals("Bob B", receivedText(harness, bob));
            hologram.destroy();
        }
    }

    @Test
    void sharedAuthoredTextParsesRichFormattingBeforeNativeMetadata() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay hologram = harness.temporary("rich", harness.at(world, 0, 64, 0), 60000L);
            hologram.setLines(List.of("<red>Shared</red>"));
            hologram.drive(true);
            assertEquals("§cShared", harness.onlySpawned(world).lastText());
            assertFalse(hologram.hasPublishedAnimation());
        }
    }

    private static String receivedText(CharacterizationHarness harness, PlayerHandle viewer) {
        for (int index = harness.sender.sent.size() - 1; index >= 0; index--) {
            CharacterizationHarness.Sent sent = harness.sender.sent.get(index);
            if (sent.viewers().contains(viewer.proxy)) {
                return TextUtils.content(sent.codec() == TextCodec.LEGACY
                    ? TextUtils.parseLegacy(sent.text()) : TextUtils.parse(sent.text()));
            }
        }
        throw new AssertionError("Viewer received no text");
    }

    private static float maximumWidth(Player viewer) throws ReflectiveOperationException {
        float width = 0F;
        for (Map.Entry<UUID, Player> visible : visiblePackets().entrySet()) {
            if (visible.getValue() == viewer) {
                width = Math.max(width, packetDisplays().get(visible.getKey()).scale().getX());
            }
        }
        return width;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Player> visiblePackets() throws ReflectiveOperationException {
        Field field = DisplayEntityManager.class.getDeclaredField("playerVisibility");
        field.setAccessible(true);
        return (Map<UUID, Player>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, DisplayEntity> packetDisplays() throws ReflectiveOperationException {
        Field field = DisplayEntityManager.class.getDeclaredField("displayEntities");
        field.setAccessible(true);
        return (Map<UUID, DisplayEntity>) field.get(null);
    }
}
