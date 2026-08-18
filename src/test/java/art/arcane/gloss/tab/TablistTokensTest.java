package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TablistTokensTest {
    @Test
    void substitutesPlayerToken() {
        assertEquals("&6Steve", TablistService.substituteTokens("&6$player", "Steve", "admin"));
    }

    @Test
    void substitutesGroupToken() {
        assertEquals("[admin] Steve", TablistService.substituteTokens("[$group] Steve", "Steve", "admin"));
    }

    @Test
    void substitutesBothTokensRepeatedly() {
        assertEquals(
            "admin:Steve admin:Steve",
            TablistService.substituteTokens("$group:$player $group:$player", "Steve", "admin")
        );
    }

    @Test
    void leavesTextWithoutTokensUntouched() {
        assertEquals("&7Plain", TablistService.substituteTokens("&7Plain", "Steve", "admin"));
    }

    @Test
    void nullTemplateBecomesEmpty() {
        assertEquals("", TablistService.substituteTokens(null, "Steve", "admin"));
    }

    @Test
    void nullValuesSubstituteAsEmpty() {
        assertEquals(" - ", TablistService.substituteTokens("$player - $group", null, null));
    }
}
