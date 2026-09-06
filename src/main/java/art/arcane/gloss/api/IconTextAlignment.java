package art.arcane.gloss.api;

public enum IconTextAlignment {
  CENTER((byte) 0),
  LEFT((byte) 0x08),
  RIGHT((byte) 0x10);

  private final byte textFlag;

  IconTextAlignment(byte textFlag) {
    this.textFlag = textFlag;
  }

  public byte textFlag() {
    return textFlag;
  }
}
