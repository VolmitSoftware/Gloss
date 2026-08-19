package art.arcane.gloss.api;

import org.bukkit.Material;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class HoloBlockIconTest {

  @Test
  public void acceptsBlockMaterials() {
    HoloIcon.Block icon = (HoloIcon.Block) HoloIcon.block(Material.STONE);

    assertEquals(Material.STONE, icon.material());
  }

  @Test
  public void rejectsNullWithoutRequiringAStandalonePaperRegistry() {
    assertThrows(NullPointerException.class, () -> HoloIcon.block(null));
  }
}
