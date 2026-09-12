package art.arcane.gloss.tab;

import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The compiled grid behind a tablist layout: one cell per slot, each with the deterministic
 * identity the client sorts and renders it by. The template is shared by every viewer; only the
 * rendered text differs.
 */
public final class TablistLayoutRuntime {
    public static final String SLOT_NAME_PREFIX = " gloss_slot_";
    public static final String SLOT_ID_PREFIX = "gloss:tab:";
    public static final int BASE_LIST_ORDER = 100_000;

    private final TablistDoc.Layout layout;
    private final List<Cell> cells;
    private final List<Cell> playerCells;
    private final CompiledCondition playerFilter;

    private TablistLayoutRuntime(TablistDoc.Layout layout, List<Cell> cells, List<Cell> playerCells,
                                 CompiledCondition playerFilter) {
        this.layout = layout;
        this.cells = cells;
        this.playerCells = playerCells;
        this.playerFilter = playerFilter;
    }

    public static TablistLayoutRuntime compile(TablistDoc.Layout layout) {
        if (layout == null || !layout.active()) {
            return null;
        }
        int rows = layout.rows();
        List<Cell> cells = new ArrayList<>(layout.size());
        for (int index = 0; index < layout.size(); index++) {
            cells.add(new Cell(index, slotId(index), slotName(index), listOrderFor(index), "", null, null));
        }
        for (TablistDoc.Slot slot : layout.slots()) {
            int index = indexOf(slot.column(), slot.row(), rows);
            Cell base = cells.get(index);
            cells.set(index, new Cell(base.index(), base.id(), base.name(), base.listOrder(), slot.text(),
                slot.skin(), slot.ping()));
        }
        List<Cell> players = new ArrayList<>();
        TablistDoc.Players block = layout.players();
        if (block != null) {
            for (int column = block.column(); column < block.column() + block.columns(); column++) {
                for (int row = 0; row < block.rows(); row++) {
                    players.add(cells.get(indexOf(column, row, rows)));
                }
            }
        }
        CompiledCondition filter = block == null ? null : ConditionCompiler.compile(
            new ConditionSource("tablist.layout.players.filter", block.filter()));
        return new TablistLayoutRuntime(layout, List.copyOf(cells), List.copyOf(players), filter);
    }

    public static int indexOf(int column, int row, int rows) {
        return column * rows + row;
    }

    public static UUID slotId(int index) {
        return UUID.nameUUIDFromBytes((SLOT_ID_PREFIX + index).getBytes(StandardCharsets.UTF_8));
    }

    public static String slotName(int index) {
        return SLOT_NAME_PREFIX + index;
    }

    /** Descending, so the client keeps the authored reading order rather than sorting by name. */
    public static int listOrderFor(int index) {
        return BASE_LIST_ORDER - index;
    }

    public TablistDoc.Layout layout() {
        return layout;
    }

    public ShowCondition show() {
        return layout.show();
    }

    public int size() {
        return cells.size();
    }

    public List<Cell> cells() {
        return cells;
    }

    public Cell cell(int index) {
        return cells.get(index);
    }

    public List<Cell> playerCells() {
        return playerCells;
    }

    public CompiledCondition playerFilter() {
        return playerFilter;
    }

    public boolean isPlayerCell(int index) {
        for (Cell cell : playerCells) {
            if (cell.index() == index) {
                return true;
            }
        }
        return false;
    }

    public record Cell(int index, UUID id, String name, int listOrder, String text, String skin, Integer ping) {
    }
}
