package art.arcane.gloss.glow;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.packets.PacketEventsStub;
import art.arcane.gloss.util.common.LayeredTeamAllocator;
import art.arcane.gloss.util.common.TeamAllocator;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class GlowServiceTest {
    private static final int TARGET_ENTITY_ID = 4242;
    private static final byte GLOWING = 0x40;

    private PacketEventsStub packets;
    private LayeredTeamAllocator teams;
    private Object previousServer;
    private Gloss previousPlugin;
    private Gloss plugin;
    private Player viewer;
    private Entity target;
    private GlowService glow;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        packets = PacketEventsStub.install();
        teams = new LayeredTeamAllocator();
        UUID targetId = UUID.randomUUID();
        target = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> targetId;
                case "getName" -> "target";
                case "getEntityId" -> TARGET_ENTITY_ID;
                case "isValid" -> true;
                case "isGlowing", "isSneaking", "isInvisible", "isSprinting", "isSwimming",
                     "isGliding", "isVisualFire" -> false;
                case "getFireTicks" -> 0;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        UUID viewerId = UUID.randomUUID();
        viewer = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> viewerId;
                case "getName" -> "viewer";
                case "isOnline" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        Server server = (Server) CharacterizationSupport.proxy(new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlayer" -> viewerId.equals(args[0]) ? viewer : null;
                case "getEntity" -> targetId.equals(args[0]) ? target : null;
                case "getLogger" -> CharacterizationSupport.mutedLogger();
                case "isPrimaryThread" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        previousServer = CharacterizationSupport.installServer(server);
        plugin = CharacterizationSupport.bareGloss(server);
        GlossConfigFile file = new GlossConfigFile();
        file.normalize();
        CharacterizationSupport.setField(plugin, "config", GlossConfig.from(file));
        CharacterizationSupport.setField(plugin, "teams", teams);
        previousPlugin = CharacterizationSupport.installGloss(plugin);
        glow = new GlowService(plugin);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousPlugin);
        CharacterizationSupport.restoreServer(previousServer);
        PacketEventsStub.uninstall();
    }

    @Test
    void taggingSendsTheGlowingFlagAndClaimsATeam() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);

        List<WrapperPlayServerEntityMetadata> sent =
            packets.sentTo(viewer, WrapperPlayServerEntityMetadata.class);
        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals(TARGET_ENTITY_ID, sent.getFirst().getEntityId());
        Assertions.assertEquals(GLOWING, flags(sent.getFirst()));
        Assertions.assertEquals(List.of("claim:viewer/glow/target"), teams.calls);
        Assertions.assertEquals("red", teams.styles.getFirst().color());
    }

    @Test
    void theHighestPriorityTagWins() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);
        glow.tag(viewer, target, "blue", "party", 1, 0L);

        Assertions.assertEquals("red", glow.top(viewer.getUniqueId(), target.getUniqueId()).color());
    }

    @Test
    void aHigherPriorityTagTakesOverAndUpdatesTheTeam() {
        glow.tag(viewer, target, "blue", "party", 1, 0L);
        teams.calls.clear();

        glow.tag(viewer, target, "red", "quest", 10, 0L);

        Assertions.assertEquals("red", glow.top(viewer.getUniqueId(), target.getUniqueId()).color());
        Assertions.assertTrue(teams.calls.stream().anyMatch(call -> call.startsWith("update:")),
            teams.calls.toString());
    }

    @Test
    void untaggingTheTopFallsBackToTheTagUnderneath() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);
        glow.tag(viewer, target, "blue", "party", 1, 0L);

        glow.untag(viewer, target, "quest");

        Assertions.assertEquals("blue", glow.top(viewer.getUniqueId(), target.getUniqueId()).color());
    }

    @Test
    void untaggingTheLastTagClearsTheGlowAndReleasesTheTeam() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);
        packets.clear();
        teams.calls.clear();

        glow.untag(viewer, target, "quest");

        List<WrapperPlayServerEntityMetadata> sent =
            packets.sentTo(viewer, WrapperPlayServerEntityMetadata.class);
        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals((byte) 0, flags(sent.getFirst()));
        Assertions.assertEquals(List.of("release:viewer/glow/target"), teams.calls);
        Assertions.assertNull(glow.top(viewer.getUniqueId(), target.getUniqueId()));
    }

    @Test
    void anExpiredTagIsSweptAway() {
        glow.tag(viewer, target, "red", "quest", 10, 1L);
        packets.clear();

        glow.sweep(System.currentTimeMillis() + 1000L);

        Assertions.assertNull(glow.top(viewer.getUniqueId(), target.getUniqueId()));
        Assertions.assertEquals(1, packets.sentTo(viewer, WrapperPlayServerEntityMetadata.class).size());
    }

    @Test
    void aTagWithoutATtlSurvivesTheSweep() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);

        glow.sweep(System.currentTimeMillis() + 1_000_000L);

        Assertions.assertNotNull(glow.top(viewer.getUniqueId(), target.getUniqueId()));
    }

    @Test
    void aServerMetadataUpdateGetsTheGlowBitPutBack() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);
        List<EntityData<?>> metadata = new ArrayList<>();
        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x02));

        Assertions.assertTrue(glow.reassert(viewer.getUniqueId(), TARGET_ENTITY_ID, metadata));

        Assertions.assertEquals((byte) (0x02 | GLOWING), (byte) metadata.getFirst().getValue());
    }

    @Test
    void anUntaggedEntityIsLeftAloneByTheListener() {
        List<EntityData<?>> metadata = new ArrayList<>();
        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x02));

        Assertions.assertFalse(glow.reassert(viewer.getUniqueId(), TARGET_ENTITY_ID, metadata));
        Assertions.assertEquals((byte) 0x02, (byte) metadata.getFirst().getValue());
    }

    @Test
    void metadataWithoutTheFlagsIndexIsLeftAlone() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);
        List<EntityData<?>> metadata = new ArrayList<>();
        metadata.add(new EntityData<>(9, EntityDataTypes.INT, 3));

        Assertions.assertFalse(glow.reassert(viewer.getUniqueId(), TARGET_ENTITY_ID, metadata));
    }

    @Test
    void forgettingAViewerClearsEveryTagItHeld() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);
        teams.calls.clear();

        glow.forget(viewer.getUniqueId());

        Assertions.assertNull(glow.top(viewer.getUniqueId(), target.getUniqueId()));
        Assertions.assertEquals(List.of("release:viewer/glow/target"), teams.calls);
    }

    @Test
    void aGlowOnASubjectWithANameplateLeavesTheSuppressionLayerAlone() {
        teams.claim(viewer, "nameplate", "target", new TeamAllocator.TeamStyle("", "", "white",
            TeamAllocator.NameTagVisibility.NEVER, TeamAllocator.CollisionRule.ALWAYS));

        glow.tag(viewer, target, "red", "quest", 10, 0L);

        Map<String, TeamAllocator.TeamStyle> layers = teams.layersFor(viewer.getUniqueId(), "target");
        Assertions.assertEquals(Set.of("nameplate", "glow"), layers.keySet(),
            "a client keeps one team per entry, so the glow must not evict the suppression");
        Assertions.assertEquals(TeamAllocator.NameTagVisibility.NEVER,
            layers.get("nameplate").nameTagVisibility());
        Assertions.assertEquals("red", layers.get("glow").color());
    }

    @Test
    void untaggingLeavesANameplateSuppressionStanding() {
        teams.claim(viewer, "nameplate", "target", new TeamAllocator.TeamStyle("", "", "white",
            TeamAllocator.NameTagVisibility.NEVER, TeamAllocator.CollisionRule.ALWAYS));
        glow.tag(viewer, target, "red", "quest", 10, 0L);

        glow.untag(viewer, target, "quest");

        Assertions.assertEquals(Set.of("nameplate"),
            teams.layersFor(viewer.getUniqueId(), "target").keySet());
    }

    @Test
    void aViewerWithNoTagsIsCheapToAskAndLeavesNothingBehind() {
        Assertions.assertFalse(glow.hasTags(viewer.getUniqueId()),
            "the metadata listener must be able to bail out before it decodes the packet");

        glow.tag(viewer, target, "red", "quest", 10, 0L);
        Assertions.assertTrue(glow.hasTags(viewer.getUniqueId()));
        Assertions.assertTrue(glow.tagged(viewer.getUniqueId(), TARGET_ENTITY_ID));

        glow.untag(viewer, target, "quest");

        Assertions.assertFalse(glow.hasTags(viewer.getUniqueId()),
            "the entity id of a released tag must not be held for the rest of the server's uptime");
        Assertions.assertFalse(glow.tagged(viewer.getUniqueId(), TARGET_ENTITY_ID));
    }

    @Test
    void forgettingAViewerDropsTheirTaggedEntityIds() {
        glow.tag(viewer, target, "red", "quest", 10, 0L);

        glow.forget(viewer.getUniqueId());

        Assertions.assertFalse(glow.hasTags(viewer.getUniqueId()));
    }

    private static byte flags(WrapperPlayServerEntityMetadata packet) {
        for (EntityData<?> data : packet.getEntityMetadata()) {
            if (data.getIndex() == 0) {
                return (byte) data.getValue();
            }
        }
        throw new AssertionError("no entity flags in " + packet.getEntityMetadata());
    }
}
