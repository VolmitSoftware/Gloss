package art.arcane.gloss.util.common;

import art.arcane.gloss.util.common.TeamAllocator.CollisionRule;
import art.arcane.gloss.util.common.TeamAllocator.NameTagVisibility;
import art.arcane.gloss.util.common.TeamAllocator.TeamHandle;
import art.arcane.gloss.util.common.TeamAllocator.TeamStyle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every per-viewer team in Gloss comes from here, so the names never collide with each other or
 * with the collisionless team display entities already use.
 */
class PacketTeamAllocatorTest {
    @BeforeAll
    static void installPacketEventsApi() {
        StubPacketEventsApi.install();
    }

    @AfterAll
    static void clearPacketEventsApi() {
        StubPacketEventsApi.clear();
    }

    private final List<String> sends = new ArrayList<>();
    private final PacketTeamAllocator teams = new PacketTeamAllocator((viewer, packet) ->
        sends.add(viewer.getName() + " " + packet.getTeamMode() + " " + packet.getTeamName() + " "
            + packet.getPlayers()));

    @Test
    void claimCreatesATeamHoldingOnlyThatEntry() {
        Player viewer = player("viewer");

        TeamHandle handle = teams.claim(viewer, "nametag", "Notch", style("&7[VIP] ", "red"));

        assertEquals(List.of("viewer CREATE " + handle.teamName() + " [Notch]"), sends);
        assertEquals("Notch", handle.entry());
        assertEquals(viewer.getUniqueId(), handle.viewerId());
    }

    @Test
    void updateReusesTheTeamAndReleaseRemovesIt() {
        Player viewer = player("viewer");
        TeamHandle handle = teams.claim(viewer, "nametag", "Notch", style("&7[VIP] ", "red"));
        sends.clear();

        teams.update(handle, style("&c[Staff] ", "red"));
        teams.release(handle);

        assertEquals(List.of("viewer UPDATE " + handle.teamName() + " []",
            "viewer REMOVE " + handle.teamName() + " []"), sends);
    }

    @Test
    void claimingTheSamePurposeAndEntryTwiceKeepsOneTeam() {
        Player viewer = player("viewer");

        TeamHandle first = teams.claim(viewer, "nametag", "Notch", style("&7", "red"));
        TeamHandle second = teams.claim(viewer, "nametag", "Notch", style("&7", "red"));

        assertEquals(first.teamName(), second.teamName());
    }

    @Test
    void everyPurposeSharesOneTeamForTheSameEntry() {
        Player viewer = player("viewer");

        TeamHandle nametag = teams.claim(viewer, "nametag", "Notch", style("&7", "red"));
        TeamHandle glow = teams.claim(viewer, "glow", "Notch", style("", "green"));
        TeamHandle nameplate = teams.claim(viewer, "nameplate", "Notch", suppression());

        assertEquals(nametag.teamName(), glow.teamName(),
            "a client keeps an entry in one team only, so a second team silently cancels the first");
        assertEquals(nametag.teamName(), nameplate.teamName());
    }

    @Test
    void allThreeConsumersComposeIntoTheOneTeamTheClientKeeps() {
        Player viewer = player("viewer");
        List<WrapperPlayServerTeams> captured = new ArrayList<>();
        PacketTeamAllocator allocator = new PacketTeamAllocator((target, packet) -> captured.add(packet));

        allocator.claim(viewer, "nametag", "Notch", new TeamStyle("&7[VIP] ", " &7*", "white",
            NameTagVisibility.ALWAYS, CollisionRule.ALWAYS));
        allocator.claim(viewer, "nameplate", "Notch", suppression());
        allocator.claim(viewer, "glow", "Notch", style("", "red"));

        WrapperPlayServerTeams.ScoreBoardTeamInfo info = captured.getLast().getTeamInfo().orElseThrow();
        assertEquals("red", info.getColor().toString(), "the glow colour must survive");
        assertEquals(WrapperPlayServerTeams.NameTagVisibility.NEVER, info.getTagVisibility(),
            "the nameplate suppression must survive");
        assertEquals(TextUtils.parse("&7[VIP] "), info.getPrefix(), "the nametag prefix must survive");
        assertEquals(TextUtils.parse(" &7*"), info.getSuffix());
        assertEquals(1, captured.stream().filter(packet ->
            packet.getTeamMode() == WrapperPlayServerTeams.TeamMode.CREATE).count());
    }

    @Test
    void releasingOneLayerLeavesTheOthersInEffect() {
        Player viewer = player("viewer");
        List<WrapperPlayServerTeams> captured = new ArrayList<>();
        PacketTeamAllocator allocator = new PacketTeamAllocator((target, packet) -> captured.add(packet));
        allocator.claim(viewer, "nameplate", "Notch", suppression());
        TeamHandle glow = allocator.claim(viewer, "glow", "Notch", style("", "red"));
        captured.clear();

        allocator.release(glow);

        assertEquals(1, captured.size());
        assertEquals(WrapperPlayServerTeams.TeamMode.UPDATE, captured.getFirst().getTeamMode());
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = captured.getFirst().getTeamInfo().orElseThrow();
        assertEquals(WrapperPlayServerTeams.NameTagVisibility.NEVER, info.getTagVisibility());
        assertEquals("white", info.getColor().toString());
    }

    @Test
    void releasingTheLastLayerRemovesTheTeam() {
        Player viewer = player("viewer");
        TeamHandle nameplate = teams.claim(viewer, "nameplate", "Notch", suppression());
        TeamHandle glow = teams.claim(viewer, "glow", "Notch", style("", "red"));
        sends.clear();

        teams.release(glow);
        teams.release(nameplate);

        assertEquals(List.of("viewer UPDATE " + glow.teamName() + " []",
            "viewer REMOVE " + glow.teamName() + " []"), sends);
    }

    @Test
    void everyTeamNameIsUniqueAndNeverCollidesWithTheCollisionlessTeam() {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < 64; index++) {
            Player viewer = player("viewer" + index);
            names.add(teams.claim(viewer, "nametag", "Notch", style("&7", "red")).teamName());
            names.add(teams.claim(viewer, "glow", "Notch", style("&7", "red")).teamName());
            names.add(teams.claim(viewer, "nametag", "Jeb", style("&7", "red")).teamName());
        }

        assertEquals(128, names.size());
        for (String name : names) {
            assertTrue(name.startsWith("gls_"), name);
            assertFalse(name.startsWith("gls_nc_"), name);
            assertTrue(name.length() <= 16, name + " is longer than the protocol allows");
        }
    }

    @Test
    void releaseAllDropsOnePurposeAndLeavesTheOtherLayersDrawn() {
        Player viewer = player("viewer");
        teams.claim(viewer, "nametag", "Notch", style("&7", "red"));
        TeamHandle jeb = teams.claim(viewer, "nametag", "Jeb", style("&7", "red"));
        TeamHandle glow = teams.claim(viewer, "glow", "Notch", style("", "green"));
        sends.clear();

        teams.releaseAll(viewer, "nametag");

        assertEquals(2, sends.size(), sends.toString());
        assertTrue(sends.contains("viewer REMOVE " + jeb.teamName() + " []"), sends.toString());
        assertTrue(sends.contains("viewer UPDATE " + glow.teamName() + " []"), sends.toString());
    }

    @Test
    void forgetDropsBookkeepingWithoutTouchingTheViewer() {
        Player viewer = player("viewer");
        TeamHandle handle = teams.claim(viewer, "nametag", "Notch", style("&7", "red"));
        sends.clear();

        teams.forget(viewer.getUniqueId());

        assertEquals(List.of(), sends);
        assertNotEquals(handle.teamName(),
            teams.claim(viewer, "nametag", "Notch", style("&7", "red")).teamName(),
            "a forgotten viewer starts a fresh team rather than reusing a name the client dropped");
    }

    @Test
    void theTeamInfoCarriesTheStyleTheCallerAsked() {
        Player viewer = player("viewer");
        List<WrapperPlayServerTeams> captured = new ArrayList<>();
        PacketTeamAllocator allocator = new PacketTeamAllocator((target, packet) -> captured.add(packet));

        allocator.claim(viewer, "nametag", "Notch", new TeamStyle("&c[Staff] ", " &c*", "red",
            NameTagVisibility.HIDE_FOR_OTHER_TEAMS, CollisionRule.NEVER));

        WrapperPlayServerTeams.ScoreBoardTeamInfo info = captured.get(0).getTeamInfo().orElseThrow();
        assertEquals(WrapperPlayServerTeams.NameTagVisibility.HIDE_FOR_OTHER_TEAMS, info.getTagVisibility());
        assertEquals(WrapperPlayServerTeams.CollisionRule.NEVER, info.getCollisionRule());
        assertEquals("red", info.getColor().toString());
    }

    private static TeamStyle suppression() {
        return new TeamStyle("", "", "white", NameTagVisibility.NEVER, CollisionRule.ALWAYS);
    }

    private static TeamStyle style(String prefix, String color) {
        return new TeamStyle(prefix, "", color, NameTagVisibility.ALWAYS, CollisionRule.ALWAYS);
    }

    private static Player player(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> name;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
