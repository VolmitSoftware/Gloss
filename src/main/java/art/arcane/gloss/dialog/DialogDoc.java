package art.arcane.gloss.dialog;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A client-rendered dialog screen. The document carries every field of the five protocol shapes;
 * which half is read is decided by {@link #type}, and the compact constructor refuses a document
 * whose type is missing the parts that shape requires rather than shipping a screen with no
 * buttons on it.
 *
 * <p>Variants replace {@code title}, {@code body} and {@code buttons} wholesale, on the board
 * model: highest priority wins, ties break on the lowest id, and nothing merges.
 */
public record DialogDoc(int schemaVersion, long revision, DialogType type, String title, String externalTitle,
                        Boolean canCloseWithEscape, Boolean pause, String afterAction,
                        List<DialogBody> body, List<DialogInput> inputs, List<DialogButton> buttons,
                        DialogButton yes, DialogButton no, DialogButton exit,
                        Integer columns, Integer buttonWidth, List<String> dialogs, Fallback fallback,
                        ShowCondition show, Selection select, List<Variant> variants) {
    public static final String KIND = "dialogs";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String AFTER_CLOSE = "close";
    public static final String AFTER_NONE = "none";
    public static final String AFTER_WAIT = "wait_for_response";
    public static final int DEFAULT_COLUMNS = 2;
    public static final int MAX_COLUMNS = 16;
    public static final int DEFAULT_BUTTON_WIDTH = 150;
    public static final int MAX_BUTTONS = 128;
    public static final int MAX_BODY = 64;
    public static final int MAX_INPUTS = 32;

    public DialogDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        type = type == null ? DialogType.NOTICE : type;
        title = title == null ? "" : title;
        externalTitle = externalTitle == null || externalTitle.isBlank() ? null : externalTitle;
        canCloseWithEscape = canCloseWithEscape == null || canCloseWithEscape;
        pause = pause != null && pause;
        afterAction = normalizeAfterAction(afterAction);
        if (pause && afterAction.equals(AFTER_NONE)) {
            throw new IllegalArgumentException("a dialog that sets pause must not use afterAction none; "
                + "the client would stay paused with no way out");
        }
        body = copyBody(body);
        inputs = copyInputs(inputs);
        buttons = copyButtons(buttons);
        columns = columns == null ? DEFAULT_COLUMNS : Math.clamp(columns.intValue(), 1, MAX_COLUMNS);
        buttonWidth = DialogWidths.clamp(buttonWidth, DEFAULT_BUTTON_WIDTH);
        dialogs = dialogs == null ? List.of() : List.copyOf(dialogs);
        show = show == null ? ShowCondition.ALWAYS : show;
        select = select == null ? Selection.NEVER : select;
        variants = copyVariants(variants);
        requireTypeParts(type, buttons, yes, no, dialogs);
    }

    public static DialogDoc parse(String fileName, String raw) {
        DialogDoc doc = DocumentParsers.parseJson(fileName, raw, DialogDoc.class);
        doc.precompileActions();
        return doc;
    }

    public DialogDoc withRevision(long revision) {
        return new DialogDoc(schemaVersion, revision, type, title, externalTitle, canCloseWithEscape, pause,
            afterAction, body, inputs, buttons, yes, no, exit, columns, buttonWidth, dialogs, fallback,
            show, select, variants);
    }

    /**
     * The buttons this document's presentation offers, in click-index order. Confirmation dialogs
     * index yes as 0 and no as 1; every other type indexes {@code buttons} directly and the exit
     * button last.
     */
    public List<DialogButton> clickableButtons() {
        List<DialogButton> ordered = new ArrayList<>(buttons.size() + 3);
        if (type == DialogType.CONFIRMATION) {
            ordered.add(yes);
            ordered.add(no);
        } else {
            ordered.addAll(buttons);
        }
        if (exit != null) {
            ordered.add(exit);
        }
        return List.copyOf(ordered);
    }

    /**
     * Compiles every button's action list once at load, so an unresolvable action or an invalid
     * {@code when} is reported against the file instead of silently doing nothing on click.
     */
    private void precompileActions() {
        String owner = KIND + ":" + type.name().toLowerCase(Locale.ROOT);
        List<DialogButton> ordered = clickableButtons();
        for (int index = 0; index < ordered.size(); index++) {
            resolve(ordered.get(index).actions(), owner, "button:" + index);
        }
        for (Variant variant : variants) {
            List<DialogButton> variantButtons = variant.presentation().buttons();
            for (int index = 0; index < variantButtons.size(); index++) {
                resolve(variantButtons.get(index).actions(), owner, "variant:" + variant.id() + "/button:" + index);
            }
        }
    }

    private static void resolve(List<MenuActionData> actions, String owner, String componentId) {
        MenuAction.resolve(actions, owner, componentId);
    }

    private static String normalizeAfterAction(String afterAction) {
        String value = afterAction == null ? AFTER_CLOSE : afterAction.trim().toLowerCase(Locale.ROOT);
        if (!value.equals(AFTER_CLOSE) && !value.equals(AFTER_NONE) && !value.equals(AFTER_WAIT)) {
            throw new IllegalArgumentException("dialog afterAction must be close, none or wait_for_response: " + afterAction);
        }
        return value;
    }

    private static void requireTypeParts(DialogType type, List<DialogButton> buttons, DialogButton yes,
                                         DialogButton no, List<String> dialogs) {
        switch (type) {
            case NOTICE -> {
                if (buttons.size() > 1) {
                    throw new IllegalArgumentException("a notice dialog declares at most one button, got " + buttons.size());
                }
            }
            case CONFIRMATION -> {
                if (yes == null || no == null) {
                    throw new IllegalArgumentException("a confirmation dialog requires both yes and no buttons");
                }
            }
            case MULTI_ACTION -> {
                if (buttons.isEmpty()) {
                    throw new IllegalArgumentException("a multi_action dialog requires at least one entry in buttons");
                }
            }
            case DIALOG_LIST -> {
                if (dialogs.isEmpty()) {
                    throw new IllegalArgumentException("a dialog_list dialog requires at least one id in dialogs");
                }
            }
            case SERVER_LINKS -> {
            }
        }
    }

    private static List<DialogBody> copyBody(List<DialogBody> body) {
        if (body == null) {
            return List.of();
        }
        if (body.size() > MAX_BODY) {
            throw new IllegalArgumentException("a dialog declares at most " + MAX_BODY + " body blocks");
        }
        for (DialogBody block : body) {
            if (block == null) {
                throw new IllegalArgumentException("dialog body may not contain null entries");
            }
        }
        return List.copyOf(body);
    }

    private static List<DialogInput> copyInputs(List<DialogInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        if (inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("a dialog declares at most " + MAX_INPUTS + " inputs");
        }
        Set<String> keys = new HashSet<>(inputs.size());
        for (DialogInput input : inputs) {
            if (input == null) {
                throw new IllegalArgumentException("dialog inputs may not contain null entries");
            }
            if (!keys.add(input.key())) {
                throw new IllegalArgumentException("dialog input key is duplicated: " + input.key());
            }
        }
        return List.copyOf(inputs);
    }

    private static List<DialogButton> copyButtons(List<DialogButton> buttons) {
        if (buttons == null) {
            return List.of();
        }
        if (buttons.size() > MAX_BUTTONS) {
            throw new IllegalArgumentException("a dialog declares at most " + MAX_BUTTONS + " buttons");
        }
        for (DialogButton button : buttons) {
            if (button == null) {
                throw new IllegalArgumentException("dialog buttons may not contain null entries");
            }
        }
        return List.copyOf(buttons);
    }

    private static List<Variant> copyVariants(List<Variant> variants) {
        if (variants == null) {
            return List.of();
        }
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("dialog variants may not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("dialog variant id is duplicated: " + variant.id());
            }
        }
        return List.copyOf(variants);
    }

    /** Where an unsupported client is sent instead; both components are optional. */
    public record Fallback(String inventory, String menu) {
        public Fallback {
            inventory = blankToNull(inventory);
            menu = blankToNull(menu);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** The board selection model: this document offers itself at {@code priority} when {@code when} holds. */
    public record Selection(int priority, String when) {
        public static final Selection NEVER = new Selection(0, "false");

        public Selection {
            when = when == null || when.isBlank() ? "false" : when.trim();
            ConditionCompiler.compile(new ConditionSource("dialogs.select.when", when));
        }
    }

    /** A variant's replacement presentation; nothing merges with the base document. */
    public record Presentation(String title, List<DialogBody> body, List<DialogButton> buttons) {
        public Presentation {
            title = title == null ? "" : title;
            body = copyBody(body);
            buttons = copyButtons(buttons);
        }
    }

    /** One conditional replacement of the base presentation. */
    public record Variant(String id, int priority, String when, Presentation presentation) {
        public Variant {
            id = id == null ? "" : id.trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("dialog variant requires an id");
            }
            when = when == null || when.isBlank() ? "false" : when.trim();
            ConditionCompiler.compile(new ConditionSource("dialogs.variants." + id + ".when", when));
            if (presentation == null) {
                throw new IllegalArgumentException("dialog variant " + id + " requires a presentation");
            }
        }
    }
}
