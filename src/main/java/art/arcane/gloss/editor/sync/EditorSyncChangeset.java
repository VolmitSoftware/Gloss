package art.arcane.gloss.editor.sync;

import java.util.List;
import java.util.Objects;

/**
 * A publish: only what the editor changed, each entry with the hash it started from. Everything a changeset does
 * not mention stays as the server has it.
 */
public record EditorSyncChangeset(List<DocumentChange> documents, List<ImageChange> images) {
  public EditorSyncChangeset {
    documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    images = List.copyOf(Objects.requireNonNull(images, "images"));
  }

  public int size() {
    return documents.size() + images.size();
  }

  /** A put (with {@code json}) or a delete of one document; {@code baseHash} null means create. */
  public record DocumentChange(boolean delete, String kind, String id, String baseHash, String json) {
    public DocumentChange {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(id, "id");
      if (delete == (json != null)) {
        throw new IllegalArgumentException("a document put carries json and a delete does not");
      }
    }
  }

  /** A put (with {@code hash} of uploaded bytes) or a delete of one image path. */
  public record ImageChange(boolean delete, String path, String baseHash, String hash, String mediaType) {
    public ImageChange {
      Objects.requireNonNull(path, "path");
      if (delete == (hash != null)) {
        throw new IllegalArgumentException("an image put carries a hash and a delete does not");
      }
    }
  }
}
