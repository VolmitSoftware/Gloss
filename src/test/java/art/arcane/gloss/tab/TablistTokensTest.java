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

    @Test
    void adjacentTokensSubstitute() {
        assertEquals("SteveadminSteve", TablistService.substituteTokens("$player$group$player", "Steve", "admin"));
    }

    @Test
    void aDollarThatStartsNoTokenIsLeftAlone() {
        assertEquals("cost $5 for Steve", TablistService.substituteTokens("cost $5 for $player", "Steve", "admin"));
        assertEquals("$", TablistService.substituteTokens("$", "Steve", "admin"));
        assertEquals("$play", TablistService.substituteTokens("$play", "Steve", "admin"));
    }

    @Test
    void substitutedValuesAreNotRescannedForOtherTokens() {
        assertEquals("$group", TablistService.substituteTokens("$player", "$group", "admin"));
        assertEquals("$player", TablistService.substituteTokens("$group", "Steve", "$player"));
    }

    @Test
    void emptyTemplateStaysEmpty() {
        assertEquals("", TablistService.substituteTokens("", "Steve", "admin"));
    }
}
