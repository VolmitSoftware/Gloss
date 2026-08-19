package art.arcane.gloss.panel;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class PanelDomainTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000201");

  @Test
  public void nestedIdsAreCanonicalAndPortable() {
    assertEquals("spawn/shops/main-1", PanelIds.canonicalize("  Spawn/Shops/Main-1  "));
    assertEquals("Menus/Shop.Main", PanelIds.requireMenuReference(" Menus/Shop.Main "));
    assertEquals(PanelIds.MAX_ID_LENGTH,
        PanelIds.canonicalize("a".repeat(63) + "/" + "b".repeat(64) + "/" + "c".repeat(64) + "/" + "d".repeat(61)).length());
  }

  @Test
  public void idsRejectTraversalAndAmbiguousPaths() {
    String[] invalid = {"../outside", "a/../../outside", "/absolute", "a//b", "a/./b", "a\\b", ".hidden", "a/%2e%2e/b"};
    for (String id : invalid) {
      assertThrows(id, IllegalArgumentException.class, () -> PanelIds.canonicalize(id));
    }
    assertThrows(IllegalArgumentException.class, () -> PanelIds.canonicalize("a".repeat(65)));
    assertThrows(IllegalArgumentException.class, () -> PanelIds.canonicalize(" "));
    assertThrows(IllegalArgumentException.class, () -> PanelIds.canonicalize(null));
    assertThrows(IllegalArgumentException.class, () -> PanelIds.requireMenuReference("menus/../shop"));
    assertThrows(IllegalArgumentException.class, () -> PanelIds.requireMenuReference("menus//shop"));
  }

  @Test
  public void transformsRequireCompleteFiniteWorldDataAndNormalizeAngles() {
    PanelTransform transform = new PanelTransform("Example:Lobby/Main", WORLD_UUID, -0.0D, 64.5D, 3.0D,
        540.0D, -540.0D, 360.0D, 1.25D);

    assertEquals("example:lobby/main", transform.worldKey());
    assertEquals(WORLD_UUID, transform.worldUuid());
    assertEquals(0.0D, transform.x(), 0.0D);
    assertEquals(-180.0D, transform.yaw(), 0.0D);
    assertEquals(-180.0D, transform.pitch(), 0.0D);
    assertEquals(0.0D, transform.roll(), 0.0D);

    assertThrows(NullPointerException.class,
        () -> PanelTransform.at("example:lobby", null, 0.0D, 0.0D, 0.0D, 0.0D));
    assertThrows(IllegalArgumentException.class,
        () -> PanelTransform.at(null, WORLD_UUID, 0.0D, 0.0D, 0.0D, 0.0D));
    assertThrows(IllegalArgumentException.class,
        () -> PanelTransform.at("lobby", WORLD_UUID, 0.0D, 0.0D, 0.0D, 0.0D));
    assertThrows(IllegalArgumentException.class,
        () -> new PanelTransform("example:lobby", WORLD_UUID, Double.NaN, 0.0D, 0.0D,
            0.0D, 0.0D, 0.0D, 1.0D));
    assertThrows(IllegalArgumentException.class,
        () -> new PanelTransform("example:lobby", WORLD_UUID, 0.0D, 0.0D, 0.0D,
            0.0D, 0.0D, 0.0D, PanelTransform.MIN_SCALE - 0.01D));
  }

  @Test
  public void followAndVisibilityRecordsEnforceCoherentStates() {
    assertEquals(PanelFollow.none(), new PanelFollow(PanelFollowMode.NONE, null, PanelFollowRotation.FIXED));
    assertEquals(PLAYER_UUID, PanelFollow.player(PLAYER_UUID, PanelFollowRotation.YAW).targetPlayerUuid());
    assertThrows(IllegalArgumentException.class,
        () -> new PanelFollow(PanelFollowMode.PLAYER, null, PanelFollowRotation.YAW));
    assertThrows(IllegalArgumentException.class,
        () -> new PanelFollow(PanelFollowMode.NONE, PLAYER_UUID, PanelFollowRotation.FIXED));

    PanelVisibility permission = PanelVisibility.permission(" Gloss.Panel.View.Spawn ", "gloss.panel.use.spawn");
    assertEquals("gloss.panel.view.spawn", permission.viewPermission());
    assertEquals("gloss.panel.use.spawn", permission.interactPermission());
    assertEquals(PanelVisibility.DEFAULT_VIEW_RANGE, permission.viewRange(), 0.0D);
    assertEquals(PanelVisibility.DEFAULT_INTERACTION_RANGE, permission.interactionRange(), 0.0D);
    assertEquals(24.0D, permission.withRanges(24.0D, 4.5D).viewRange(), 0.0D);
    PanelVisibility maximum = permission.withRanges(
        PanelVisibility.MAX_VIEW_RANGE,
        PanelVisibility.MAX_INTERACTION_RANGE
    );
    assertEquals(PanelVisibility.MAX_VIEW_RANGE, maximum.viewRange(), 0.0D);
    assertEquals(PanelVisibility.MAX_INTERACTION_RANGE, maximum.interactionRange(), 0.0D);
    assertThrows(IllegalArgumentException.class,
        () -> new PanelVisibility(PanelVisibilityMode.PERMISSION, null, null, 24.0D, 4.5D));
    assertThrows(IllegalArgumentException.class,
        () -> new PanelVisibility(PanelVisibilityMode.HIDDEN, null, "gloss.panel.use", 24.0D, 4.5D));
    assertThrows(IllegalArgumentException.class, () -> permission.withRanges(Double.NaN, 4.5D));
    assertThrows(IllegalArgumentException.class, () -> permission.withRanges(24.0D, 0.0D));
    assertThrows(IllegalArgumentException.class, () -> permission.withRanges(4.0D, 4.5D));
    assertThrows(IllegalArgumentException.class,
        () -> permission.withRanges(PanelVisibility.MAX_VIEW_RANGE + 1.0D, 4.5D));
    assertThrows(IllegalArgumentException.class,
        () -> permission.withRanges(64.0D, PanelVisibility.MAX_INTERACTION_RANGE + 1.0D));
  }

  @Test
  public void definitionsStartVersionedWithIndependentStableIdentities() {
    PanelTransform transform = PanelTransform.at("example:missing_world", WORLD_UUID, 1.0D, 2.0D, 3.0D, 45.0D);
    PanelDefinition first = PanelDefinition.create("Boards/Main", "Shop", transform);
    PanelDefinition second = PanelDefinition.create("Boards/Other", "Shop", transform);

    assertEquals(PanelDefinition.CURRENT_SCHEMA_VERSION, first.schemaVersion());
    assertEquals(PanelDefinition.INITIAL_REVISION, first.revision());
    assertEquals("boards/main", first.id());
    assertEquals("Shop", first.rootMenuId());
    assertNotEquals(first.uuid(), second.uuid());
    assertEquals(first.uuid(), first.withVisibility(PanelVisibility.hidden()).uuid());
    assertEquals(first.revision(), first.withVisibility(PanelVisibility.hidden()).revision());

    assertThrows(IllegalArgumentException.class,
        () -> new PanelDefinition(0, "boards/main", first.uuid(), 1L, "Shop", transform,
            PanelFollow.none(), PanelVisibility.publicAccess()));
    assertThrows(IllegalArgumentException.class,
        () -> new PanelDefinition(1, "boards/main", first.uuid(), 0L, "Shop", transform,
            PanelFollow.none(), PanelVisibility.publicAccess()));
  }

  @Test
  public void everyRejectionAPlayerCanSeeSpeaksThePanelVocabulary() {
    PanelTransform transform = PanelTransform.at("example:missing_world", WORLD_UUID, 1.0D, 2.0D, 3.0D, 45.0D);

    assertEquals("unsupported panel schemaVersion: 0",
        assertThrows(IllegalArgumentException.class,
            () -> new PanelDefinition(0, "panels/main", UUID.randomUUID(), 1L, "Shop", transform,
                PanelFollow.none(), PanelVisibility.publicAccess())).getMessage());
    assertEquals("hidden panels cannot declare interactPermission",
        assertThrows(IllegalArgumentException.class,
            () -> new PanelVisibility(PanelVisibilityMode.HIDDEN, null, "gloss.panel.use",
                PanelVisibility.DEFAULT_VIEW_RANGE, PanelVisibility.DEFAULT_INTERACTION_RANGE)).getMessage());
    assertEquals("player-following panels require targetPlayerUuid",
        assertThrows(IllegalArgumentException.class,
            () -> new PanelFollow(PanelFollowMode.PLAYER, null, PanelFollowRotation.FIXED)).getMessage());
  }
}
