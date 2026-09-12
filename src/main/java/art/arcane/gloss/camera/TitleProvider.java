package art.arcane.gloss.camera;

import org.bukkit.entity.Player;

/**
 * The letterbox seam. A camera ride asks for black bars while it runs; the screen lane's surface
 * delivery implements this, and until a lane registers one the bars are simply not drawn.
 */
public interface TitleProvider {
    void letterbox(Player viewer, boolean visible);
}
