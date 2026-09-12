package art.arcane.gloss.dialog;

/**
 * Where a viewer goes when the dialog protocol is not available to them: an old client, or a
 * Bedrock viewer no forms API could reach. The inventory wins over the menu because a chest renders
 * natively on every client Gloss supports.
 */
public record DialogFallback(Target target, String id) {
    public enum Target {
        NONE,
        INVENTORY,
        MENU
    }

    public static final DialogFallback NONE = new DialogFallback(Target.NONE, null);

    public static DialogFallback choose(DialogDoc.Fallback fallback) {
        if (fallback == null) {
            return NONE;
        }
        if (fallback.inventory() != null) {
            return new DialogFallback(Target.INVENTORY, fallback.inventory());
        }
        if (fallback.menu() != null) {
            return new DialogFallback(Target.MENU, fallback.menu());
        }
        return NONE;
    }
}
