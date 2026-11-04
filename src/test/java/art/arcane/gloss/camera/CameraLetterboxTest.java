package art.arcane.gloss.camera;

import art.arcane.gloss.text.TextDisplayLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraLetterboxTest {
    private static final double WIDEST_FOV = Math.tan(Math.toRadians(110D / 2D));

    @Test
    void theBarsMirrorEachOtherAcrossTheViewCenter() {
        assertEquals(CameraLetterbox.topBarY(), -(CameraLetterbox.bottomBarY() + CameraLetterbox.BAR_HEIGHT), 1.0E-6F);
    }

    @Test
    void theBarsCoverTheViewPastTheWidestSetting() {
        double top = CameraLetterbox.topBarY() + CameraLetterbox.BAR_HEIGHT;

        assertTrue(top / CameraLetterbox.DEPTH > WIDEST_FOV);
        assertTrue(CameraLetterbox.BAR_WIDTH / 2D / CameraLetterbox.DEPTH > WIDEST_FOV * 32D / 9D);
    }

    @Test
    void theScaleTurnsOneBlankCellIntoTheBarSize() {
        assertEquals(CameraLetterbox.BAR_WIDTH, CameraLetterbox.scaleX() * 5 * TextDisplayLayout.PIXEL_SIZE, 1.0E-3F);
        assertEquals(CameraLetterbox.BAR_HEIGHT, CameraLetterbox.scaleY() * TextDisplayLayout.ROW_PIXELS * TextDisplayLayout.PIXEL_SIZE, 1.0E-3F);
    }
}
