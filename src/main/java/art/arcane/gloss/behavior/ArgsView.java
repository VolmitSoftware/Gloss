package art.arcane.gloss.behavior;

import art.arcane.gloss.menu.action.ActionContext;

import java.util.Map;

/**
 * The trigger arguments of the behavior program running on this thread. The program installs them
 * for the duration of each action so {@code args.<name>} resolves in text a behavior renders
 * through another document's scope; {@link art.arcane.gloss.menu.ArgsNamespace} reads them.
 */
public final class ArgsView {
    public static final String PREFIX = "args";

    /** A context that carries trigger arguments; the program installs them while its actions run. */
    public interface Source {
        Map<String, Object> args();
    }

    private static final ThreadLocal<Map<String, Object>> CURRENT = new ThreadLocal<>();

    private ArgsView() {
    }

    /** The arguments of the program running on this thread, or null outside one. */
    public static Map<String, Object> current() {
        return CURRENT.get();
    }

    /** @return the previously installed args, to hand back to {@link #exit} */
    public static Map<String, Object> enter(ActionContext context) {
        Map<String, Object> previous = CURRENT.get();
        if (context instanceof Source source) {
            CURRENT.set(source.args());
        }
        return previous;
    }

    public static void exit(Map<String, Object> previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
