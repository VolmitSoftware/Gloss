package art.arcane.gloss.api;

public enum IconBillboard {
  FIXED((byte) 0),
  VERTICAL((byte) 1),
  HORIZONTAL((byte) 2),
  CENTER((byte) 3);

  private final byte metadataValue;

  IconBillboard(byte metadataValue) {
    this.metadataValue = metadataValue;
  }

  public byte metadataValue() {
    return metadataValue;
  }
}
