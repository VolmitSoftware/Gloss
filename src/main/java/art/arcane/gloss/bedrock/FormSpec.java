package art.arcane.gloss.bedrock;

import java.util.List;

/**
 * A dialog in the two shapes Geyser can draw: a column of buttons, or a column of controls with one
 * submit. The spec is deliberately free of any Geyser type so the mapping is testable on a server
 * that has never heard of Bedrock.
 */
public sealed interface FormSpec permits FormSpec.Simple, FormSpec.Custom {

    String title();

    /** The click indexes each button maps back to, so a Bedrock press runs the same actions. */
    List<Integer> buttonIndexes();

    /** A column of buttons. What a dialog with no inputs becomes. */
    record Simple(String title, String content, List<String> buttons, List<Integer> buttonIndexes)
        implements FormSpec {
        public Simple {
            buttons = List.copyOf(buttons);
            buttonIndexes = List.copyOf(buttonIndexes);
        }
    }

    /** A column of controls with one submit. What a dialog with inputs becomes. */
    record Custom(String title, String content, List<Control> controls, List<String> buttons,
                  List<Integer> buttonIndexes) implements FormSpec {
        public Custom {
            controls = List.copyOf(controls);
            buttons = List.copyOf(buttons);
            buttonIndexes = List.copyOf(buttonIndexes);
        }
    }

    /** One control of a custom form; the key is the dialog input key the answer belongs to. */
    sealed interface Control permits Input, Toggle, Slider, Dropdown {
        String key();

        String label();
    }

    record Input(String key, String label, String initial) implements Control {
    }

    record Toggle(String key, String label, boolean initial) implements Control {
    }

    record Slider(String key, String label, float min, float max, float step, float initial)
        implements Control {
    }

    record Dropdown(String key, String label, List<String> options, List<String> ids, int initialIndex)
        implements Control {
        public Dropdown {
            options = List.copyOf(options);
            ids = List.copyOf(ids);
        }
    }
}
