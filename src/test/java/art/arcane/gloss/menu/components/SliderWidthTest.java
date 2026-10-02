package art.arcane.gloss.menu.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.SliderComponentData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.MenuSessionOptions;
import art.arcane.gloss.menu.MenuTransform;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliderWidthTest {
    @Test
    void configuredWidthControlsTheClickableAreaAtMenuScale() throws MenuIconException {
        Location anchor = new Location(null, 0D, 0D, 5D);
        MenuTransform transform = new MenuTransform(anchor, new Vector(), 0F, 0F, 0F, 2F);
        MenuDefinitionData definition = new MenuDefinitionData(new Vector(), false, false, null,
            false, false, List.of(), List.of(), null, Map.of(), List.of());
        MenuSession session = new MenuSession(definition, null,
            MenuSessionOptions.positioned(transform, request -> NavigationResult.DENIED, 2F));
        SliderComponentData data = new SliderComponentData("volume", 0F, 100F, 1F, 3F, "Volume", null);
        SliderComponent slider = new SliderComponent(session,
            new MenuComponentData("volume", new Vector(), data, null));
        slider.currentIcon = new LabelIcon(session, anchor);

        CollisionPlane hitbox = slider.createHitbox();

        assertEquals(6F, hitbox.getWidth());
        assertEquals(0.5F, hitbox.getHeight());
        assertTrue(hitbox.intersectionDistance(new Vector(2D, 0D, 0D), new Vector(0D, 0D, 1D)).isPresent());
        assertFalse(hitbox.intersectionDistance(new Vector(4D, 0D, 0D), new Vector(0D, 0D, 1D)).isPresent());
    }

    private static final class LabelIcon extends MenuIcon<TextIconData> {
        private LabelIcon(MenuSession session, Location location) throws MenuIconException {
            super(session, location, new TextIconData("Volume", null, null, null));
        }

        @Override
        protected List<UUID> createDisplayEntities(Location location) {
            return List.of();
        }

        @Override
        public CollisionPlane createBoundingBox(Location anchor) {
            return new CollisionPlane(anchor.toVector(), 0.5F, 0.5F);
        }
    }
}
