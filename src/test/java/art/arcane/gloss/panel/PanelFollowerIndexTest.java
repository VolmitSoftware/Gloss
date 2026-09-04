package art.arcane.gloss.panel;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class PanelFollowerIndexTest {
  private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000701");
  private static final UUID FIRST_TARGET = UUID.fromString("00000000-0000-0000-0000-000000000702");
  private static final UUID SECOND_TARGET = UUID.fromString("00000000-0000-0000-0000-000000000703");

  @Test
  public void indexContainsOnlyPanelsForEachExactFollowTarget() {
    PanelDefinition staticPanel = board("static");
    PanelDefinition first = board("first").withFollow(
        PanelFollow.player(FIRST_TARGET, PanelFollowRotation.FIXED));
    PanelDefinition second = board("second").withFollow(
        PanelFollow.player(FIRST_TARGET, PanelFollowRotation.YAW));
    PanelDefinition third = board("third").withFollow(
        PanelFollow.player(SECOND_TARGET, PanelFollowRotation.FULL));

    Map<UUID, Set<UUID>> followers = PanelRuntimeManager.indexFollowers(
        List.of(staticPanel, first, second, third));

    assertEquals(Set.of(first.uuid(), second.uuid()), followers.get(FIRST_TARGET));
    assertEquals(Set.of(third.uuid()), followers.get(SECOND_TARGET));
    assertFalse(followers.values().stream().anyMatch(ids -> ids.contains(staticPanel.uuid())));
    assertThrows(UnsupportedOperationException.class,
        () -> followers.get(FIRST_TARGET).add(UUID.randomUUID()));
    assertThrows(UnsupportedOperationException.class,
        () -> followers.put(UUID.randomUUID(), Set.of()));
  }

  private static PanelDefinition board(String id) {
    return new PanelDefinition(PanelDefinition.CURRENT_SCHEMA_VERSION, id,
        UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
        PanelDefinition.INITIAL_REVISION, "menu",
        PanelTransform.at("example:world", WORLD, 0.0D, 64.0D, 0.0D, 0.0D),
        PanelFollow.none(), PanelVisibility.publicAccess(), ShowCondition.ALWAYS);
  }
}
