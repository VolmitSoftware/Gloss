package art.arcane.gloss.marker;

import art.arcane.gloss.condition.ShowCondition;

/** The {@code audience { when }} block markers and waypoints share. */
public record Audience(ShowCondition when) {
    public static final Audience ALWAYS = new Audience(ShowCondition.ALWAYS);

    public Audience {
        when = when == null ? ShowCondition.ALWAYS : when;
    }
}
