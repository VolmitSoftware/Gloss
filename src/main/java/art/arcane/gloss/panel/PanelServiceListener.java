package art.arcane.gloss.panel;

import java.util.List;

public interface PanelServiceListener {
  default void boardCreated(PanelDefinition board) {
  }

  default void boardUpdated(PanelDefinition previous, PanelDefinition updated) {
  }

  default void boardDeleted(PanelDefinition board) {
  }

  default void boardsReloaded(PanelLoadResult result, List<PanelDefinition> boards) {
  }
}
