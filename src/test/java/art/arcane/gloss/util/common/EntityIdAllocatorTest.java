package art.arcane.gloss.util.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityIdAllocatorTest {
    @Test
    void idsAscendFromTheNegativePool() {
        EntityIdAllocator allocator = new EntityIdAllocator();
        int first = allocator.next();
        int second = allocator.next();
        assertEquals(Integer.MIN_VALUE, first);
        assertEquals(first + 1, second);
    }

    @Test
    void globalPoolIsSharedAndMonotonic() {
        int before = EntityIdAllocator.global().next();
        int after = EntityIdAllocator.global().next();
        assertTrue(after > before);
    }
}
