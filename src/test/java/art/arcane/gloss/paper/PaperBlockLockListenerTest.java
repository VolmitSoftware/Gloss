package art.arcane.gloss.paper;

import art.arcane.gloss.preview.ContainerPreviewAccess;
import io.papermc.paper.event.block.BlockLockCheckEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperBlockLockListenerTest {

    @Test
    void theListenerLoadsAgainstPapersLockCheckEvent() throws ReflectiveOperationException {
        Class<?> type = Class.forName("art.arcane.gloss.paper.PaperBlockLockListener");

        Object listener = type.getConstructor().newInstance();

        assertInstanceOf(ContainerPreviewAccess.LockCheck.class, listener);
    }

    @Test
    void paperDeclaresTheLockCheckConstructorTheListenerLooksFor() {
        Constructor<?> resolved = null;
        for (Constructor<?> candidate : BlockLockCheckEvent.class.getConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length >= 2 && parameters[0] == Block.class && parameters[1] == Player.class) {
                resolved = candidate;
            }
        }

        assertNotNull(resolved, "Paper no longer exposes a (Block, Player, ...) BlockLockCheckEvent constructor");
        assertTrue(resolved.getParameterCount() >= 2);
    }
}
