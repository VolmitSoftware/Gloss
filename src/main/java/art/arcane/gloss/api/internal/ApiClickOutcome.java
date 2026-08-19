package art.arcane.gloss.api.internal;

public enum ApiClickOutcome {
  DISPATCHED,
  FAULTED,
  NO_HANDLER,
  SKIPPED_OWNER_DISABLED,
  SKIPPED_QUARANTINED
}
