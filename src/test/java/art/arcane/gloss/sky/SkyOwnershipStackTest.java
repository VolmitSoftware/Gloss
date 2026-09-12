package art.arcane.gloss.sky;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

class SkyOwnershipStackTest {
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void anEmptyStackOwnsNothing() {
        Assertions.assertEquals(Optional.empty(), new SkyOwnershipStack().top(PLAYER));
    }

    @Test
    void theNewestOverrideWins() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));
        stack.push(PLAYER, override("quest", 6000L));

        Assertions.assertEquals("quest", stack.top(PLAYER).orElseThrow().purpose());
    }

    @Test
    void releasingTheTopRestoresThePreviousOwner() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));
        stack.push(PLAYER, override("quest", 6000L));

        stack.release(PLAYER, "quest");

        Assertions.assertEquals("arena", stack.top(PLAYER).orElseThrow().purpose());
    }

    @Test
    void releasingAPurposeUnderTheTopKeepsTheTop() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));
        stack.push(PLAYER, override("quest", 6000L));

        stack.release(PLAYER, "arena");

        Assertions.assertEquals("quest", stack.top(PLAYER).orElseThrow().purpose());
    }

    @Test
    void pushingTheSamePurposeTwiceMovesItToTheTop() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));
        stack.push(PLAYER, override("quest", 6000L));

        stack.push(PLAYER, override("arena", 1000L));

        Assertions.assertEquals("arena", stack.top(PLAYER).orElseThrow().purpose());
        Assertions.assertEquals(1000L, stack.top(PLAYER).orElseThrow().time());
    }

    @Test
    void releasingTheLastPurposeEmptiesThePlayer() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));

        stack.release(PLAYER, "arena");

        Assertions.assertEquals(Optional.empty(), stack.top(PLAYER));
        Assertions.assertFalse(stack.owns(PLAYER));
    }

    @Test
    void releasingAnUnknownPurposeChangesNothing() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));

        stack.release(PLAYER, "nowhere");

        Assertions.assertEquals("arena", stack.top(PLAYER).orElseThrow().purpose());
    }

    @Test
    void stacksAreIndependentPerPlayer() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        UUID other = UUID.randomUUID();
        stack.push(PLAYER, override("arena", 18000L));

        Assertions.assertEquals(Optional.empty(), stack.top(other));
    }

    @Test
    void forgettingAPlayerDropsEveryOverride() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));
        stack.push(PLAYER, override("quest", 6000L));

        stack.forget(PLAYER);

        Assertions.assertEquals(Optional.empty(), stack.top(PLAYER));
    }

    @Test
    void purposesAreListedNewestFirst() {
        SkyOwnershipStack stack = new SkyOwnershipStack();
        stack.push(PLAYER, override("arena", 18000L));
        stack.push(PLAYER, override("quest", 6000L));

        Assertions.assertEquals(java.util.List.of("quest", "arena"), stack.purposes(PLAYER));
    }

    private static SkyOverride override(String purpose, long time) {
        return new SkyOverride(purpose, time, null, null, 0);
    }
}
