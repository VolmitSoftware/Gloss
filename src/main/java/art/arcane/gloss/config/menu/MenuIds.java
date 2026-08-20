package art.arcane.gloss.config.menu;

import art.arcane.gloss.doc.DocumentIds;

public final class MenuIds {
  public static final int MAX_ID_LENGTH = DocumentIds.MAX_ID_LENGTH;
  public static final int MAX_SEGMENT_LENGTH = DocumentIds.MAX_SEGMENT_LENGTH;

  private MenuIds() {
  }

  public static String require(String value) {
    return DocumentIds.require("menu id", value);
  }
}
