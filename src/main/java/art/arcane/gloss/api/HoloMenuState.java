package art.arcane.gloss.api;

public enum HoloMenuState {
  PENDING,
  OPEN,
  CLOSED,
  FAILED;

  public boolean terminal() {
    return this == CLOSED || this == FAILED;
  }
}
