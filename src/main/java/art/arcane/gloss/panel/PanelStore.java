package art.arcane.gloss.panel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

interface PanelStore {
  Path directory();

  PanelLoadResult load() throws IOException;

  Optional<PanelDefinition> get(String id);

  List<PanelDefinition> list();

  PanelDefinition create(PanelDefinition definition) throws IOException;

  PanelDefinition update(String id, long expectedRevision,
                         UnaryOperator<PanelDefinition> update) throws IOException;

  PanelDefinition rename(String id, String newId, long expectedRevision) throws IOException;

  PanelDefinition delete(String id, long expectedRevision) throws IOException;

  PanelDefinition publishExternalCreate(PanelDefinition created) throws IOException;

  PanelDefinition recoverExternalCreate(PanelDefinition created) throws IOException;

  PanelDefinition publishExternal(PanelDefinition expected, PanelDefinition updated) throws IOException;

  PanelDefinition recoverExternal(PanelDefinition applied, PanelDefinition restored) throws IOException;
}
