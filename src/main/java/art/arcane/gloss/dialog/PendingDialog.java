package art.arcane.gloss.dialog;

import art.arcane.gloss.dialog.DialogRuntime.Rendered;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one dialog a player currently has open. Opening a second retires the first, so a token that
 * does not match the pending one is a screen the server has already moved on from.
 *
 * <p>The token is also single-use: {@link #claim()} hands it to exactly one arriving payload, so a
 * client that resends the packet the server gave it cannot run the button's actions twice. A screen
 * that stays up past its press ({@code afterAction: none}) is re-armed once its run finishes, which
 * bounds a replay to one action list per server round trip.</p>
 */
public final class PendingDialog {
    private final UUID player;
    private final String docId;
    private final String token;
    private final long openedAtMs;
    private final Map<String, Object> args;
    private final Rendered rendered;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    public PendingDialog(UUID player, String docId, String token, long openedAtMs,
                         Map<String, Object> args, Rendered rendered) {
        this.player = player;
        this.docId = docId;
        this.token = token;
        this.openedAtMs = openedAtMs;
        this.args = args;
        this.rendered = rendered;
    }

    /** @return true for the one caller that took the press; every later copy of it gets false */
    public boolean claim() {
        return armed.compareAndSet(true, false);
    }

    /** Puts the same token back in play for a screen the client still has in front of them. */
    public void rearm() {
        armed.set(true);
    }

    public boolean armed() {
        return armed.get();
    }

    public UUID player() {
        return player;
    }

    public String docId() {
        return docId;
    }

    public String token() {
        return token;
    }

    public long openedAtMs() {
        return openedAtMs;
    }

    public Map<String, Object> args() {
        return args;
    }

    public Rendered rendered() {
        return rendered;
    }
}
