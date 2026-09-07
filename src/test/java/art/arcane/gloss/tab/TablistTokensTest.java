package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TablistTokensTest {
    static Stream<Arguments> substitutions() {
        return Stream.of(
            Arguments.of("substitutesPlayerToken", "&6$player", "Steve", "admin", "&6Steve"),
            Arguments.of("substitutesGroupToken", "[$group] Steve", "Steve", "admin", "[admin] Steve"),
            Arguments.of("substitutesBothTokensRepeatedly", "$group:$player $group:$player", "Steve", "admin",
                "admin:Steve admin:Steve"),
            Arguments.of("leavesTextWithoutTokensUntouched", "&7Plain", "Steve", "admin", "&7Plain"),
            Arguments.of("nullTemplateBecomesEmpty", null, "Steve", "admin", ""),
            Arguments.of("nullValuesSubstituteAsEmpty", "$player - $group", null, null, " - "),
            Arguments.of("adjacentTokensSubstitute", "$player$group$player", "Steve", "admin", "SteveadminSteve"),
            Arguments.of("emptyTemplateStaysEmpty", "", "Steve", "admin", "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("substitutions")
    void substituteTokensResolvesTemplates(String label, String template, String player, String group,
                                           String expected) {
        assertEquals(expected, TablistService.substituteTokens(template, player, group), label);
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
}
