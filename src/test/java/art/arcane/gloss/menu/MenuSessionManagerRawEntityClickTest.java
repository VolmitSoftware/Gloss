package art.arcane.gloss.menu;

import art.arcane.gloss.api.HoloClickTrigger;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MenuSessionManagerRawEntityClickTest {

  @Test
  public void attackPacketsMapToLeftClickTriggers() {
    assertEquals(HoloClickTrigger.LEFT_CLICK, MenuSessionManager.rawEntityTrigger(
        WrapperPlayClientInteractEntity.InteractAction.ATTACK,
        false
    ));
    assertEquals(HoloClickTrigger.SHIFT_LEFT_CLICK, MenuSessionManager.rawEntityTrigger(
        WrapperPlayClientInteractEntity.InteractAction.ATTACK,
        true
    ));
  }

  @Test
  public void interactionPacketsMapToRightClickTriggers() {
    assertEquals(HoloClickTrigger.RIGHT_CLICK, MenuSessionManager.rawEntityTrigger(
        WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT,
        false
    ));
    assertEquals(HoloClickTrigger.SHIFT_RIGHT_CLICK, MenuSessionManager.rawEntityTrigger(
        WrapperPlayClientInteractEntity.InteractAction.INTERACT,
        true
    ));
  }
}
