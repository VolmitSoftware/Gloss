package art.arcane.gloss.command;

import art.arcane.gloss.panel.PanelDefinition;
import art.arcane.gloss.panel.PanelTransform;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class PanelEditSessionTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000411");

  @Test
  public void stagesDefinitionAndEffectiveTransformAsOneSnapshot() {
    PanelDefinition base = board();
    PanelEditSession session = new PanelEditSession(base, base.transform());
    PanelEditSession.Snapshot expected = session.snapshot();
    PanelTransform effective = new PanelTransform(
        "example:world", WORLD_UUID, 9D, 8D, 7D, 6D, 5D, 4D, 2D);
    PanelDefinition changed = base.withRootMenu("secondary").withTransform(effective);

    PanelEditSession.Snapshot staged = session.stage(expected, changed, effective);

    assertEquals(changed, staged.definition());
    assertEquals(effective, staged.effectiveTransform());
    assertEquals(staged, session.snapshot());
  }

  @Test
  public void stalePreparedMutationCannotOverwriteNewerStagedState() {
    PanelDefinition base = board();
    PanelEditSession session = new PanelEditSession(base, base.transform());
    PanelEditSession.Snapshot stale = session.snapshot();
    PanelDefinition first = base.withRootMenu("first");
    session.stage(stale, first, base.transform());

    assertThrows(IllegalStateException.class,
        () -> session.stage(stale, base.withRootMenu("second"), base.transform()));
  }

  @Test
  public void saveLocksFurtherStagesUntilAFailedSaveIsRetried() {
    PanelDefinition base = board();
    PanelEditSession session = new PanelEditSession(base, base.transform());

    assertEquals(base, session.beginSave());
    assertNull(session.beginSave());
    assertNull(session.stage(session.snapshot(), base.withRootMenu("blocked"), base.transform()));

    session.retrySave();
    PanelEditSession.Snapshot staged = session.stage(
        session.snapshot(), base.withRootMenu("allowed"), base.transform());
    assertEquals("allowed", staged.definition().rootMenuId());
  }

  @Test
  public void stagedEditsCannotChangeIdentityOrRevision() {
    PanelDefinition base = board();
    PanelEditSession session = new PanelEditSession(base, base.transform());
    PanelDefinition changedIdentity = new PanelDefinition(
        base.schemaVersion(), "other", base.uuid(), base.revision(), base.rootMenuId(),
        base.transform(), base.follow(), base.visibility());

    assertThrows(IllegalArgumentException.class,
        () -> session.stage(session.snapshot(), changedIdentity, base.transform()));
  }

  private static PanelDefinition board() {
    return PanelDefinition.create("board", "main", new PanelTransform(
        "example:world", WORLD_UUID, 1D, 2D, 3D, 4D, 5D, 6D, 1D));
  }
}
