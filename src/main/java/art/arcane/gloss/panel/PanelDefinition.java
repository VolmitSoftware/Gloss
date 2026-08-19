package art.arcane.gloss.panel;

import java.util.Objects;
import java.util.UUID;

public record PanelDefinition(int schemaVersion, String id, UUID uuid, long revision, String rootMenuId,
                              PanelTransform transform, PanelFollow follow, PanelVisibility visibility) {
  public static final int CURRENT_SCHEMA_VERSION = 1;
  public static final long INITIAL_REVISION = 1L;
  public static final long MAX_SAFE_REVISION = 9_007_199_254_740_991L;

  public PanelDefinition {
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported panel schemaVersion: " + schemaVersion);
    }
    id = PanelIds.canonicalize(id);
    uuid = Objects.requireNonNull(uuid, "uuid");
    if (revision < INITIAL_REVISION || revision > MAX_SAFE_REVISION) {
      throw new IllegalArgumentException("revision must be between " + INITIAL_REVISION
          + " and " + MAX_SAFE_REVISION);
    }
    rootMenuId = PanelIds.requireMenuReference(rootMenuId);
    transform = Objects.requireNonNull(transform, "transform");
    follow = Objects.requireNonNull(follow, "follow");
    visibility = Objects.requireNonNull(visibility, "visibility");
  }

  public static PanelDefinition create(String id, String rootMenuId, PanelTransform transform) {
    return new PanelDefinition(CURRENT_SCHEMA_VERSION, id, UUID.randomUUID(), INITIAL_REVISION, rootMenuId,
        transform, PanelFollow.none(), PanelVisibility.publicAccess());
  }

  public PanelDefinition withRootMenu(String rootMenuId) {
    return new PanelDefinition(schemaVersion, id, uuid, revision, rootMenuId, transform, follow, visibility);
  }

  public PanelDefinition withTransform(PanelTransform transform) {
    return new PanelDefinition(schemaVersion, id, uuid, revision, rootMenuId, transform, follow, visibility);
  }

  public PanelDefinition withFollow(PanelFollow follow) {
    return new PanelDefinition(schemaVersion, id, uuid, revision, rootMenuId, transform, follow, visibility);
  }

  public PanelDefinition withVisibility(PanelVisibility visibility) {
    return new PanelDefinition(schemaVersion, id, uuid, revision, rootMenuId, transform, follow, visibility);
  }

  PanelDefinition withRevision(long revision) {
    return new PanelDefinition(schemaVersion, id, uuid, revision, rootMenuId, transform, follow, visibility);
  }
}
