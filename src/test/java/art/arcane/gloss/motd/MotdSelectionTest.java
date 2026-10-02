package art.arcane.gloss.motd;

import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotdSelectionTest {
    private static final ExprScope EMPTY = new ExprScope() {
        @Override
        public Object variable(String name) {
            return null;
        }

        @Override
        public Object call(String name, List<Object> arguments) {
            return null;
        }
    };

    @Test
    void hiddenEntriesNeverParticipateInWeightedSelection() {
        MotdDoc doc = MotdDoc.parse("motd.json", """
            {"schemaVersion":1,"revision":1,"entries":[
              {"lines":["Hidden"],"show":false,"weight":1000000},
              {"lines":["Visible"],"weight":1}]}
            """);
        Random random = new Random(9L);
        for (int index = 0; index < 100; index++) {
            assertEquals(1, MotdService.selectEntry(doc.entries(), EMPTY, random));
        }
        assertEquals(-1, MotdService.selectEntry(List.of(doc.entries().getFirst()), EMPTY, random));
    }

    @Test
    void weightsAffectTheDistribution() {
        MotdDoc doc = MotdDoc.parse("motd.json", """
            {"schemaVersion":1,"revision":1,"entries":[
              {"lines":["Rare"],"weight":1},{"lines":["Common"],"weight":9}]}
            """);
        Random random = new Random(21L);
        int common = 0;
        for (int index = 0; index < 10000; index++) {
            common += MotdService.selectEntry(doc.entries(), EMPTY, random) == 1 ? 1 : 0;
        }
        assertTrue(common > 8800 && common < 9200);
        assertThrows(IllegalArgumentException.class, () -> MotdDoc.parse("motd.json", """
            {"schemaVersion":1,"revision":1,"entries":[{"lines":["Invalid"],"weight":0}]}
            """));
    }
}
