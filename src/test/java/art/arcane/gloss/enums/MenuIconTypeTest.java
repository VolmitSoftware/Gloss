package art.arcane.gloss.enums;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MenuIconTypeTest {

  @Test
  public void theUnimplementedFontImageTypeIsGone() {
    assertThrows(IllegalArgumentException.class, () -> MenuIconType.valueOf("FONT_IMAGE"));
    assertTrue(Arrays.stream(MenuIconType.values()).noneMatch(t -> t.getSerializedName().equals("fontImage")));
  }

  @Test
  public void theApiOnlyItemStackTypeStays() {
    assertEquals("itemStack", MenuIconType.ITEM_STACK.getSerializedName());
    assertNull(MenuIconType.ITEM_STACK.getType());
  }

  @Test
  public void everyOtherTypeDeclaresItsPayloadRecord() {
    for (MenuIconType type : MenuIconType.values()) {
      if (type == MenuIconType.ITEM_STACK)
        continue;
      assertNotNull(type.name(), type.getType());
    }
  }
}
