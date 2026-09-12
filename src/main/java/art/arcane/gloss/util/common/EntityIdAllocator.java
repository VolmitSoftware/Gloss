package art.arcane.gloss.util.common;

import art.arcane.gloss.Gloss;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Process-wide packet entity id pool. Ids ascend from {@link Integer#MIN_VALUE} so they never meet
 * the server's own ascending ids; every packet display in Gloss draws from this one pool, which is
 * why animator and manager maps can key on the id alone. {@link #nextBlock} hands a rig its part
 * ids contiguously so a multi-part model can be destroyed with one packet.
 */
public final class EntityIdAllocator {
    private static final EntityIdAllocator GLOBAL = new EntityIdAllocator();

    private final AtomicInteger next = new AtomicInteger(Integer.MIN_VALUE);

    public static EntityIdAllocator global() {
        return GLOBAL;
    }

    public int next() {
        return next.getAndUpdate(current -> {
            if (++current < 0) {
                return current;
            }
            Gloss.log(Level.SEVERE, "Entity IDs overflow");
            Gloss.log(Level.SEVERE, "Please restart your server!");
            return Integer.MIN_VALUE;
        });
    }

    /** @return {@code count} ascending ids; contiguous unless the pool wrapped mid-block */
    public int[] nextBlock(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = next();
        }
        return ids;
    }
}
