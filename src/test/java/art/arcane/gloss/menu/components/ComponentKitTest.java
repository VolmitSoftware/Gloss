package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.components.FieldComponentData;
import art.arcane.gloss.config.components.SliderComponentData;
import art.arcane.gloss.config.components.TabsComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.SessionVariables;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the three writing components put into the session, without the display entities in the way.
 * A slider that could step past its own range, or a tab row that could select a tab it does not
 * have, is a state an author can never recover from inside the menu.
 */
class ComponentKitTest {

    @Test
    void aSliderStepsWithinItsRange() {
        SliderComponentData slider = new SliderComponentData("volume", 0F, 100F, 5F, 2.0F, "&7Volume", null);
        assertEquals(MenuComponentType.SLIDER, slider.getType());

        assertEquals(55D, SliderComponent.step(slider, 50D, HoloClickTrigger.LEFT_CLICK));
        assertEquals(45D, SliderComponent.step(slider, 50D, HoloClickTrigger.RIGHT_CLICK));
        assertEquals(75D, SliderComponent.step(slider, 50D, HoloClickTrigger.SHIFT_LEFT_CLICK));
        assertEquals(25D, SliderComponent.step(slider, 50D, HoloClickTrigger.SHIFT_RIGHT_CLICK));
    }

    @Test
    void aSliderClampsAtBothEnds() {
        SliderComponentData slider = new SliderComponentData("volume", 0F, 100F, 5F, 2.0F, null, null);
        assertEquals(100D, SliderComponent.step(slider, 100D, HoloClickTrigger.LEFT_CLICK));
        assertEquals(0D, SliderComponent.step(slider, 0D, HoloClickTrigger.RIGHT_CLICK));
        assertEquals(100D, SliderComponent.step(slider, 90D, HoloClickTrigger.SHIFT_LEFT_CLICK));
    }

    @Test
    void aSliderWithAnInvertedRangeIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new SliderComponentData("volume", 100F, 0F, 5F, 2.0F, null, null));
    }

    @Test
    void aSliderWritesItsVariableOnClick() {
        SessionVariables variables = SessionVariables.of(Map.of("volume", 50.0D), Map.of());
        SliderComponentData slider = new SliderComponentData("volume", 0F, 100F, 5F, 2.0F, null, null);

        SliderComponent.apply(slider, variables, HoloClickTrigger.LEFT_CLICK);
        assertEquals(55.0D, variables.get("volume"));
    }

    @Test
    void aTabRowWritesTheTabThatWasClicked() {
        TabsComponentData tabs = new TabsComponentData("tab",
            List.of(new TabsComponentData.Tab("weapons", "&fWeapons"), new TabsComponentData.Tab("armor", "&fArmor")),
            0.5F, null);
        assertEquals(MenuComponentType.TABS, tabs.getType());

        SessionVariables variables = SessionVariables.of(Map.of("tab", "weapons"), Map.of());
        TabsComponent.select(tabs, variables, 1);
        assertEquals("armor", variables.get("tab"));
    }

    @Test
    void aTabRowIgnoresATabItDoesNotHave() {
        TabsComponentData tabs = new TabsComponentData("tab",
            List.of(new TabsComponentData.Tab("weapons", "&fWeapons")), 0.5F, null);
        SessionVariables variables = SessionVariables.of(Map.of("tab", "weapons"), Map.of());

        TabsComponent.select(tabs, variables, 4);
        assertEquals("weapons", variables.get("tab"));
    }

    @Test
    void aTabRowNeedsAtLeastOneTab() {
        assertThrows(IllegalArgumentException.class, () -> new TabsComponentData("tab", List.of(), 0.5F, null));
    }

    @Test
    void aFieldNamesThePromptItOpens() {
        FieldComponentData field = new FieldComponentData("search", "sign", "&7Search", "", null);
        assertEquals(MenuComponentType.FIELD, field.getType());
        assertEquals("sign", field.prompt());
        assertEquals("search", field.variable());
    }

    @Test
    void aFieldWithAnUnknownPromptKindIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new FieldComponentData("search", "hologram", null, null, null));
    }
}
