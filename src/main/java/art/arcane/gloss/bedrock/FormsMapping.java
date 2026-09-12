package art.arcane.gloss.bedrock;

import art.arcane.gloss.dialog.DialogInput;
import art.arcane.gloss.dialog.DialogRuntime;
import art.arcane.volmlib.util.format.ColorFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a rendered dialog into the Bedrock form it will be drawn as. Bedrock has no rich text, so
 * every label is stripped to plain characters, and an item body has no form equivalent at all: its
 * description survives as a line of content and the item itself is dropped rather than guessed at.
 */
public final class FormsMapping {
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&\u00a7][0-9A-FK-ORX]");

    private FormsMapping() {
    }

    public static FormSpec map(DialogRuntime.Rendered rendered) {
        String title = plain(rendered.title());
        String content = content(rendered);
        List<String> buttons = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (DialogRuntime.Rendered.Button button : rendered.buttons()) {
            buttons.add(plain(button.label()));
            indexes.add(button.index());
        }
        if (rendered.exit() != null) {
            buttons.add(plain(rendered.exit().label()));
            indexes.add(rendered.exit().index());
        }
        if (rendered.inputs().isEmpty()) {
            return new FormSpec.Simple(title, content, buttons, indexes);
        }
        return new FormSpec.Custom(title, content, controls(rendered), buttons, indexes);
    }

    private static List<FormSpec.Control> controls(DialogRuntime.Rendered rendered) {
        List<FormSpec.Control> controls = new ArrayList<>(rendered.inputs().size());
        for (DialogRuntime.Rendered.Input input : rendered.inputs()) {
            controls.add(control(input));
        }
        return controls;
    }

    private static FormSpec.Control control(DialogRuntime.Rendered.Input input) {
        DialogInput source = input.source();
        String label = plain(input.label());
        return switch (source.type()) {
            case DialogInput.BOOL -> new FormSpec.Toggle(source.key(), label, source.initialBoolean());
            case DialogInput.NUMBER -> new FormSpec.Slider(source.key(), label, source.start(), source.end(),
                source.step() == null ? 1F : source.step(),
                source.initialNumber() == null ? source.start() : source.initialNumber());
            case DialogInput.OPTION -> dropdown(source, label);
            default -> new FormSpec.Input(source.key(), label, source.initial());
        };
    }

    private static FormSpec.Dropdown dropdown(DialogInput source, String label) {
        List<String> options = new ArrayList<>(source.options().size());
        List<String> ids = new ArrayList<>(source.options().size());
        int initial = 0;
        for (int index = 0; index < source.options().size(); index++) {
            DialogInput.Option option = source.options().get(index);
            options.add(plain(option.label() == null || option.label().isEmpty() ? option.id() : option.label()));
            ids.add(option.id());
            if (option.initial()) {
                initial = index;
            }
        }
        return new FormSpec.Dropdown(source.key(), label, options, ids, initial);
    }

    private static String content(DialogRuntime.Rendered rendered) {
        List<String> lines = new ArrayList<>(rendered.body().size());
        for (DialogRuntime.Rendered.Body block : rendered.body()) {
            String line = block.item() ? block.description() : block.text();
            if (line != null && !line.isEmpty()) {
                lines.add(plain(line));
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Bedrock forms draw no formatting. Both the section codes the pipeline produced and the
     * ampersand codes an author wrote are removed, because either one shown literally is worse on
     * screen than the colour being missing.
     */
    static String plain(String text) {
        return text == null ? "" : LEGACY_CODE.matcher(ColorFormatter.stripColor(text)).replaceAll("");
    }
}
