package art.arcane.gloss.config.menu;

import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.doc.DocumentHashes;

import java.util.Objects;

public record MenuDocument(String id, String revision, String source, MenuDefinitionData definition) {
  public MenuDocument {
    id = Objects.requireNonNull(id, "id");
    revision = Objects.requireNonNull(revision, "revision");
    source = Objects.requireNonNull(source, "source");
    definition = Objects.requireNonNull(definition, "definition");
  }

  public static String revisionOf(String source) {
    return DocumentHashes.sha256(Objects.requireNonNull(source, "source"));
  }
}
