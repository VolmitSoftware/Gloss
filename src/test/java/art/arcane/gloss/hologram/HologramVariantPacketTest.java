package art.arcane.gloss.hologram;

import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramVariantPacketTest {
    @TempDir
    File directory;

    @Test
    void variantStylePacketsTargetOnlyTheMatchingViewerAndResetOnChange() throws InterruptedException {
        PacketEventsStub packets = PacketEventsStub.install();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle alice = harness.join("Alice", world, 0, 64, 0);
            CharacterizationHarness.PlayerHandle bob = harness.join("Bob", world, 2, 64, 0);
            PersistentHologram hologram = harness.persistent("style", harness.at(world, 0, 64, 0));
            hologram.apply(HologramDoc.parse("style.json", """
                {"schemaVersion":3,"revision":1,"anchor":{"world":"world","position":[0,64,0]},
                 "lines":["base"],"variants":[{"id":"near","when":"player.x < 1",
                   "presentation":{"style":{"scaleX":2,"shadow":true}}}]}
                """));
            hologram.update();
            harness.drainDelayed();
            List<WrapperPlayServerEntityMetadata> alicePackets = packets.sentTo(alice.proxy, WrapperPlayServerEntityMetadata.class);
            assertFalse(alicePackets.isEmpty());
            assertEquals(new Vector3f(2, 1, 1), field(alicePackets.getLast(), 12));
            assertTrue((Byte) field(alicePackets.getLast(), 27) % 2 == 1);
            assertTrue(alicePackets.getLast().getEntityMetadata().stream().noneMatch(value -> value.getIndex() == 23));
            assertTrue(packets.sentTo(bob.proxy, WrapperPlayServerEntityMetadata.class).isEmpty());
            harness.moveTo(alice, world, 3, 64, 0);
            Thread.sleep(55);
            hologram.update();
            harness.drainDelayed();
            alicePackets = packets.sentTo(alice.proxy, WrapperPlayServerEntityMetadata.class);
            assertEquals(new Vector3f(1, 1, 1), field(alicePackets.getLast(), 12));
            assertTrue(harness.schedulerErrors.isEmpty());
        } finally {
            PacketEventsStub.uninstall();
        }
    }

    @Test
    void objectPacketsFollowAuthoredOrderAndVisibility() throws InterruptedException {
        PacketEventsStub packets = PacketEventsStub.install();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle alice = harness.join("Alice", world, 0, 64, 0);
            CharacterizationHarness.PlayerHandle bob = harness.join("Bob", world, 2, 64, 0);
            PersistentHologram hologram = harness.persistent("objects", harness.at(world, 0, 64, 0));
            hologram.apply(HologramDoc.parse("objects.json", """
                {"schemaVersion":3,"revision":1,"anchor":{"world":"world","position":[0,64,0]},
                 "lines":[{"entity":"minecraft:cow","show":"player.x < 1"},"middle",{"entity":"minecraft:pig"}]}
                """));
            hologram.update();
            harness.drainDelayed();
            List<WrapperPlayServerSpawnEntity> aliceSpawns = packets.sentTo(alice.proxy, WrapperPlayServerSpawnEntity.class);
            List<WrapperPlayServerSpawnEntity> bobSpawns = packets.sentTo(bob.proxy, WrapperPlayServerSpawnEntity.class);
            assertEquals(2, aliceSpawns.size());
            assertEquals(EntityTypes.COW, aliceSpawns.get(0).getEntityType());
            assertEquals(EntityTypes.PIG, aliceSpawns.get(1).getEntityType());
            assertTrue(aliceSpawns.get(0).getPosition().getY() > 64);
            assertTrue(aliceSpawns.get(1).getPosition().getY() > 64);
            assertTrue(aliceSpawns.get(0).getPosition().getY() > aliceSpawns.get(1).getPosition().getY());
            assertEquals(1, bobSpawns.size());
            assertEquals(EntityTypes.PIG, bobSpawns.getFirst().getEntityType());
            packets.clear();
            harness.moveTo(alice, world, 3, 64, 0);
            Thread.sleep(55);
            hologram.update();
            harness.drainDelayed();
            assertFalse(packets.sentTo(alice.proxy, WrapperPlayServerDestroyEntities.class).isEmpty());
            assertEquals(List.of(EntityTypes.PIG), packets.sentTo(alice.proxy, WrapperPlayServerSpawnEntity.class)
                .stream().map(WrapperPlayServerSpawnEntity::getEntityType).toList());
            assertTrue(harness.schedulerErrors.isEmpty());
        } finally {
            PacketEventsStub.uninstall();
        }
    }

    @Test
    void changingNativeTextScaleRebuildsMixedRowsAndTheirObjectPositions() {
        PacketEventsStub packets = PacketEventsStub.install();
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Alice", world, 0, 64, 0);
            PersistentHologram hologram = harness.persistent("objects", harness.at(world, 0, 64, 0));
            hologram.apply(HologramDoc.parse("objects.json", """
                {"schemaVersion":3,"revision":1,"anchor":{"world":"world","position":[0,64,0]},
                 "lines":["Title",{"entity":"minecraft:cow","scale":0.6},"Footer"]}
                """));
            hologram.update();
            harness.drainDelayed();
            List<WrapperPlayServerSpawnEntity> original = packets.sentTo(viewer.proxy,
                WrapperPlayServerSpawnEntity.class);
            assertEquals(1, original.size());
            assertEquals(64.455D, original.getFirst().getPosition().getY(), 1.0E-6D);

            packets.clear();
            hologram.setStyle(hologram.style().withScale(2F, 2F, 2F));
            hologram.update();
            harness.drainDelayed();
            List<WrapperPlayServerSpawnEntity> scaled = packets.sentTo(viewer.proxy,
                WrapperPlayServerSpawnEntity.class);
            assertEquals(1, scaled.size());
            assertEquals(64.83D, scaled.getFirst().getPosition().getY(), 1.0E-6D);
            assertFalse(packets.sentTo(viewer.proxy, WrapperPlayServerDestroyEntities.class).isEmpty());
            assertTrue(harness.schedulerErrors.isEmpty());
        } finally {
            PacketEventsStub.uninstall();
        }
    }

    private static Object field(WrapperPlayServerEntityMetadata packet, int index) {
        for (EntityData<?> value : packet.getEntityMetadata()) {
            if (value.getIndex() == index) {
                return value.getValue();
            }
        }
        throw new AssertionError("Missing metadata index " + index);
    }
}
