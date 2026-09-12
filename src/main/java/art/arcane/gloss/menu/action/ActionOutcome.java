package art.arcane.gloss.menu.action;

public enum ActionOutcome {
  CONTINUE,
  STOP,
  /** The list continues later on the run's thread; click callers treat it as {@link #STOP}. */
  SUSPENDED
}
