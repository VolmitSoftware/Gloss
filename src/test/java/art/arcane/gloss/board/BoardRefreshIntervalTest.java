package art.arcane.gloss.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoardRefreshIntervalTest {
    @Test
    void ordinaryPlaceholdersKeepConfiguredCadence() {
        GlossBoardMeta board = board("{{ player.name }}", "{{ fixed(server.tps, 1) }}", "%vault_prefix%");

        assertEquals(20, BoardService.refreshIntervalTicks(board, 20));
    }

    @Test
    void timeExpressionsAndNamedAnimationsUseEveryTick() {
        GlossBoardMeta expression = board("Static", "{{ floor(time.seconds * 4) }}");
        GlossBoardMeta named = board("|animation.rainbow|", "Static");

        assertEquals(1, BoardService.refreshIntervalTicks(expression, 20));
        assertEquals(1, BoardService.refreshIntervalTicks(named, 20));
        assertEquals(1, BoardService.refreshIntervalTicks(named, 1));
    }

    private static GlossBoardMeta board(String title, String... lines) {
        GlossBoardMeta board = new GlossBoardMeta("test");
        board.setTitle(title);
        for (String line : lines) {
            board.addLine(line);
        }
        return board;
    }
}
