package art.arcane.gloss.nameplate;

import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.util.common.LayeredTeamAllocator;
import art.arcane.gloss.util.common.TeamAllocator;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

class NameplateSuppressionTest {
    @Test
    void admittingATargetClaimsATeamThatHidesTheVanillaTag() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        Player viewer = player("viewer");
        Player target = player("target");

        suppression.admit(viewer, target, false);

        Assertions.assertEquals(List.of("claim:viewer/nameplate/target"), teams.calls);
        Assertions.assertEquals(TeamAllocator.NameTagVisibility.NEVER, teams.styles.getFirst().nameTagVisibility());
    }

    @Test
    void admittingTheSameTargetTwiceClaimsOnce() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        Player viewer = player("viewer");
        Player target = player("target");

        suppression.admit(viewer, target, false);
        suppression.admit(viewer, target, false);

        Assertions.assertEquals(1, teams.calls.size());
    }

    @Test
    void retiringATargetReleasesItsTeam() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        Player viewer = player("viewer");
        Player target = player("target");
        suppression.admit(viewer, target, false);
        teams.calls.clear();

        suppression.retire(viewer.getUniqueId(), target.getUniqueId());

        Assertions.assertEquals(List.of("release:viewer/nameplate/target"), teams.calls);
    }

    @Test
    void aBedrockViewerKeepsTheVanillaTag() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);

        suppression.admit(player("viewer"), player("target"), true);

        Assertions.assertEquals(List.of(), teams.calls);
    }

    @Test
    void forgettingAViewerReleasesEveryTeamItHeld() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        Player viewer = player("viewer");
        suppression.admit(viewer, player("a"), false);
        suppression.admit(viewer, player("b"), false);
        teams.calls.clear();

        suppression.forget(viewer);

        Assertions.assertEquals(List.of("releaseAll:viewer/nameplate"), teams.calls);
        Assertions.assertEquals(0, suppression.claimed(viewer.getUniqueId()));
    }

    @Test
    void clearingReleasesEveryViewer() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        suppression.admit(player("one"), player("a"), false);
        suppression.admit(player("two"), player("b"), false);
        teams.calls.clear();

        suppression.clear();

        Assertions.assertEquals(2, teams.calls.size());
    }

    @Test
    void aSubjectWhoLeavesOverlayRangeGetsTheirVanillaTagBack() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        Player viewer = player("viewer");
        Player target = player("target");
        suppression.admit(viewer, target, false);

        suppression.retireSubject(target.getUniqueId());

        Assertions.assertEquals(List.of("claim:viewer/nameplate/target",
            "release:viewer/nameplate/target"), teams.calls);
        Assertions.assertEquals(0, suppression.claimed(viewer.getUniqueId()));
    }

    @Test
    void aGlowOnTheSameSubjectDoesNotCancelTheSuppression() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        Player viewer = player("viewer");
        Player target = player("target");

        suppression.admit(viewer, target, false);
        teams.claim(viewer, "glow", "target", new TeamAllocator.TeamStyle("", "", "red",
            TeamAllocator.NameTagVisibility.ALWAYS, TeamAllocator.CollisionRule.ALWAYS));

        Assertions.assertEquals(Set.of("nameplate", "glow"),
            teams.layersFor(viewer.getUniqueId(), "target").keySet(),
            "a client keeps one team per entry, so a glow must not evict the suppression");
        Assertions.assertEquals(TeamAllocator.NameTagVisibility.NEVER,
            teams.layersFor(viewer.getUniqueId(), "target").get("nameplate").nameTagVisibility());
    }

    @Test
    void retiringAPairThatWasNeverClaimedIsQuiet() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);

        suppression.retire(UUID.randomUUID(), UUID.randomUUID());
        suppression.retireSubject(UUID.randomUUID());

        Assertions.assertEquals(List.of(), teams.calls);
    }

    @Test
    void aMobUsesItsUuidAsTheScoreboardEntry() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression suppression = new NameplateSuppression(teams, "entity-overlay");
        Player viewer = player("viewer");
        Entity target = entity();

        suppression.admit(viewer, target, false);

        Assertions.assertEquals(List.of("claim:viewer/entity-overlay/" + target.getUniqueId()), teams.calls);
        Assertions.assertEquals(TeamAllocator.NameTagVisibility.NEVER, teams.styles.getFirst().nameTagVisibility());
        suppression.retireSubject(target.getUniqueId());
        Assertions.assertTrue(teams.layersFor(viewer.getUniqueId(), target.getUniqueId().toString()).isEmpty());
    }

    @Test
    void forgettingOnePurposePreservesAnotherSuppressionClaim() {
        LayeredTeamAllocator teams = new LayeredTeamAllocator();
        NameplateSuppression nameplates = new NameplateSuppression(teams, NameplateSuppression.PURPOSE);
        NameplateSuppression overlays = new NameplateSuppression(teams, "entity-overlay");
        Player viewer = player("viewer");
        Player target = player("target");
        nameplates.admit(viewer, target, false);
        overlays.admit(viewer, target, false);
        teams.calls.clear();

        overlays.forget(viewer);

        Assertions.assertEquals(List.of("releaseAll:viewer/entity-overlay"), teams.calls);
        Assertions.assertEquals(Set.of(NameplateSuppression.PURPOSE),
            teams.layersFor(viewer.getUniqueId(), target.getName()).keySet());
        Assertions.assertEquals(1, nameplates.claimed(viewer.getUniqueId()));
        Assertions.assertEquals(0, overlays.claimed(viewer.getUniqueId()));
        nameplates.clear();
        Assertions.assertTrue(teams.layersFor(viewer.getUniqueId(), target.getName()).isEmpty());
    }

    private static Entity entity() {
        UUID id = UUID.randomUUID();
        return (Entity) CharacterizationSupport.proxy(new Class<?>[]{Entity.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }

    private static Player player(String name) {
        UUID id = UUID.randomUUID();
        return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }
}
