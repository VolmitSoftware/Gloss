package art.arcane.gloss.board;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.ExprParser;

import java.util.Locale;

public record BoardObjectives(Slot playerList, Slot belowName) {
    public static final BoardObjectives NONE = new BoardObjectives(null, null);

    public boolean isEmpty() {
        return playerList == null && belowName == null;
    }

    public record Slot(String title, String value, String renderType, BoardLineFormat format,
                       String valueText, ShowCondition show, ShowCondition subjects,
                       Integer refreshTicks, String conflict) {
        public Slot {
            title = title == null ? "" : title;
            value = value == null ? "subject.health" : value;
            ExprParser.parse(value);
            renderType = renderType == null ? "integer" : renderType.toLowerCase(Locale.ROOT);
            if (!renderType.equals("integer") && !renderType.equals("hearts")) {
                throw new IllegalArgumentException("Native scoreboard renderType must be integer or hearts");
            }
            format = format == null ? BoardLineFormat.NUMBER : format;
            valueText = valueText == null ? "" : valueText;
            show = show == null ? ShowCondition.ALWAYS : show;
            subjects = subjects == null ? ShowCondition.ALWAYS : subjects;
            refreshTicks = refreshTicks == null ? 20 : refreshTicks;
            if (refreshTicks < 1 || refreshTicks > 72000) {
                throw new IllegalArgumentException("Native scoreboard refreshTicks must be within 1..72000");
            }
            conflict = conflict == null ? "yield" : conflict.toLowerCase(Locale.ROOT);
            if (!conflict.equals("yield") && !conflict.equals("override")) {
                throw new IllegalArgumentException("Native scoreboard conflict must be yield or override");
            }
        }
    }
}
