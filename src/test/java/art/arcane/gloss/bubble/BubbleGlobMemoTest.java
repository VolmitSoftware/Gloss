package art.arcane.gloss.bubble;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleGlobMemoTest {
    private static final List<String> PATTERNS = List.of(
        "", "*", "world", "world*", "*world", "*world*", "w?rld", "w??ld", "world.nether",
        "w(1)+[a]", "nether*", "hub?", "the_end", "*_*", "a*b?c", "$^{}", "\\Qliteral\\E", "he\\llo");

    private static final List<String> VALUES = List.of(
        "", "world", "world_nether", "the_nether", "the_end_again", "hub1", "hub", "wrld", "woorld",
        "worldXnether", "w1a", "w(1)+[a]", "$^{}", "\\Qliteral\\E", "he\\llo", "a_b_c", "aXbYc");

    private static String referenceRegex(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current == '*' || current == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(current == '*' ? ".*" : ".");
                continue;
            }
            literal.append(current);
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    @BeforeEach
    void resetCache() {
        BubbleStyles.clearPatternCache();
    }

    @Test
    void memoizedMatchingIsIdenticalToTheUncachedRegexBuild() {
        for (String pattern : PATTERNS) {
            for (String value : VALUES) {
                assertEquals(value.matches(referenceRegex(pattern)), BubbleStyles.globMatches(pattern, value),
                    "pattern '" + pattern + "' against value '" + value + "'");
            }
        }
    }

    @Test
    void repeatedMatchesThroughTheMemoStayStable() {
        for (int repeat = 0; repeat < 5; repeat++) {
            for (String pattern : PATTERNS) {
                for (String value : VALUES) {
                    assertEquals(value.matches(referenceRegex(pattern)), BubbleStyles.globMatches(pattern, value));
                }
            }
        }
    }

    @Test
    void aClearedCacheStillProducesTheSameResults() {
        assertTrue(BubbleStyles.globMatches("world*", "world_nether"));
        BubbleStyles.clearPatternCache();
        assertTrue(BubbleStyles.globMatches("world*", "world_nether"));
    }

    @Test
    void overflowingTheMemoNeverChangesAnOutcome() {
        for (int index = 0; index < 2048; index++) {
            String pattern = "world" + index + "*";
            assertTrue(BubbleStyles.globMatches(pattern, "world" + index + "_nether"));
        }
        assertTrue(BubbleStyles.globMatches("world*", "world_nether"));
        assertFalse(BubbleStyles.globMatches("hub?", "world_nether"));
    }
}
