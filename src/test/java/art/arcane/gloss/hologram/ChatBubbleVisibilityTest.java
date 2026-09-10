package art.arcane.gloss.hologram;

import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.bubble.ChatBubblesService;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatBubbleVisibilityTest {
    @TempDir
    File directory;

    @Test
    void hiddenSenderNeverShowsItsBubbleOrAnimationToAnUnauthorizedViewer() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle sender = harness.join("Sender", world, 0.0D, 64.0D, 0.0D);
            PlayerHandle hidden = harness.join("Hidden", world, 1.0D, 64.0D, 0.0D);
            PlayerHandle staff = harness.join("Staff", world, 2.0D, 64.0D, 0.0D);
            hidden.playerVisibility.put(sender.uuid, false);
            ChatBubblesService bubbles = bubbles(harness, true, ShowCondition.ALWAYS);

            TemporaryHologramDisplay bubble = speak(harness, bubbles, sender);
            bubble.drive(true);
            bubble.drive(true);

            DisplayHandle display = harness.onlySpawned(world);
            assertEquals(Boolean.FALSE, display.visibleByDefault);
            assertEquals(0, hidden.showCallsFor(display));
            assertEquals(Boolean.FALSE, hidden.perceivedVisibility(display));
            assertEquals(Boolean.FALSE, sender.perceivedVisibility(display));
            assertEquals(Boolean.TRUE, staff.perceivedVisibility(display));
            assertEquals(1, harness.animator.pass(System.currentTimeMillis()));
            assertEquals(List.of(staff.proxy), harness.sender.sent.getLast().viewers());
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    @Test
    void liveBubbleHidesAndRestoresWhenTheViewerCanSeeStateChanges() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle sender = harness.join("Sender", world, 0.0D, 64.0D, 0.0D);
            PlayerHandle viewer = harness.join("Viewer", world, 1.0D, 64.0D, 0.0D);
            ChatBubblesService bubbles = bubbles(harness, true, ShowCondition.ALWAYS);
            TemporaryHologramDisplay bubble = speak(harness, bubbles, sender);
            bubble.drive(true);
            bubble.drive(true);
            DisplayHandle display = harness.onlySpawned(world);
            assertEquals(Boolean.TRUE, viewer.perceivedVisibility(display));
            assertEquals(1, harness.animator.pass(System.currentTimeMillis()));

            viewer.playerVisibility.put(sender.uuid, false);
            bubble.drive(true);

            assertEquals(Boolean.FALSE, viewer.perceivedVisibility(display));
            assertEquals(0, harness.animator.pass(System.currentTimeMillis()));
            int hiddenCalls = viewer.hideCallsFor(display);
            bubble.drive(true);
            assertEquals(hiddenCalls, viewer.hideCallsFor(display));

            viewer.playerVisibility.put(sender.uuid, true);
            bubble.drive(true);

            assertEquals(Boolean.TRUE, viewer.perceivedVisibility(display));
            assertEquals(1, harness.animator.pass(System.currentTimeMillis()));
            assertEquals(List.of(viewer.proxy), harness.sender.sent.getLast().viewers());
            assertEquals(1, harness.liveSpawned(world).size());
            assertFalse(display.removed);
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    @Test
    void senderVisibilityComposesWithStyleConditionsAndTheHideOwnSetting() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle sender = harness.join("Sender", world, 3.0D, 64.0D, 0.0D);
            PlayerHandle viewer = harness.join("Viewer", world, 0.0D, 64.0D, 0.0D);
            ChatBubblesService bubbles = bubbles(harness, false, ShowCondition.of("player.x > 1"));
            TemporaryHologramDisplay bubble = speak(harness, bubbles, sender);
            bubble.drive(true);
            DisplayHandle display = harness.onlySpawned(world);
            assertEquals(Boolean.TRUE, sender.perceivedVisibility(display));
            assertEquals(Boolean.FALSE, viewer.perceivedVisibility(display));

            harness.moveTo(viewer, world, 2.0D, 64.0D, 0.0D);
            viewer.playerVisibility.put(sender.uuid, false);
            bubble.drive(true);
            assertEquals(Boolean.FALSE, viewer.perceivedVisibility(display));

            viewer.playerVisibility.put(sender.uuid, true);
            bubble.drive(true);
            assertEquals(Boolean.TRUE, viewer.perceivedVisibility(display));

            harness.moveTo(viewer, world, 0.0D, 64.0D, 0.0D);
            bubble.drive(true);
            assertEquals(Boolean.FALSE, viewer.perceivedVisibility(display));
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    private ChatBubblesService bubbles(CharacterizationHarness harness, boolean hideOwn,
                                      ShowCondition show) throws Exception {
        harness.configure(config -> config.features.chatBubbles = true);
        BubbleStyleDoc defaults = BubbleStyleDoc.DEFAULTS;
        BubbleStyleDoc style = new BubbleStyleDoc(defaults.schemaVersion(), defaults.revision(), defaults.prefix(),
            defaults.offset(), defaults.wordWrapChars(), 60000L, defaults.followPlayer(), hideOwn,
            defaults.motion(), defaults.shimmer(), defaults.select(), defaults.particleLayers(), show,
            defaults.style(), defaults.box());
        Path folder = directory.toPath().resolve(BubbleStyleDoc.KIND);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("default.json"), DocumentParsers.GSON.toJson(style));
        ChatBubblesService service = new ChatBubblesService(harness.gloss);
        ((DocumentRegistry<?>) field(service, "registry")).reload();
        assertNotNull(service.style("default"), "The configured bubble style must load successfully.");
        Field accepting = ChatBubblesService.class.getDeclaredField("acceptingBubbles");
        accepting.setAccessible(true);
        accepting.setBoolean(service, true);
        return service;
    }

    private static TemporaryHologramDisplay speak(CharacterizationHarness harness, ChatBubblesService bubbles,
                                                   PlayerHandle sender) throws Exception {
        Method receive = ChatBubblesService.class.getDeclaredMethod("prepareAndSpawn", Player.class, String.class);
        receive.setAccessible(true);
        receive.invoke(bubbles, sender.proxy, "private message");
        Collection<?> active = (Collection<?>) field(harness.service, "temporaries");
        assertEquals(1, active.size());
        return (TemporaryHologramDisplay) active.iterator().next();
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
