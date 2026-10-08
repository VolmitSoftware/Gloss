package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.DialogMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record DialogActionData(String title, String kind, List<Body> body, List<Input> inputs,
                               List<Button> buttons, Button exitButton, Boolean escape, Integer columns,
                               Integer timeoutTicks, List<MenuActionData> unsupported,
                               List<MenuActionData> onTimeout, HoloClickTrigger trigger,
                               String when, Integer cooldownTicks) implements MenuActionData {
    public DialogActionData {
        title = title == null ? "" : title;
        kind = kind == null ? "notice" : kind.toLowerCase(Locale.ROOT);
        if (!Set.of("notice", "confirmation", "multi_action").contains(kind)) {
            throw new IllegalArgumentException("dialog kind must be notice, confirmation or multi_action");
        }
        body = body == null ? List.of() : List.copyOf(body);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        buttons = buttons == null ? List.of(new Button("OK", null, null, null)) : List.copyOf(buttons);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        onTimeout = onTimeout == null ? List.of() : List.copyOf(onTimeout);
        escape = escape == null || escape;
        columns = bounded(columns, 2, 1, 64, "columns");
        timeoutTicks = bounded(timeoutTicks, 1200, 1, 72000, "timeoutTicks");
        if (body.size() > 64 || inputs.size() > 64 || buttons.isEmpty() || buttons.size() > 64) {
            throw new IllegalArgumentException("dialog supports at most 64 body entries, inputs and buttons; at least one button is required");
        }
        if (kind.equals("notice") && buttons.size() != 1 || kind.equals("confirmation") && buttons.size() != 2) {
            throw new IllegalArgumentException("notice requires one button; confirmation requires two buttons");
        }
        if (exitButton != null && !kind.equals("multi_action")) {
            throw new IllegalArgumentException("exitButton is only supported by multi_action dialogs");
        }
        Set<String> keys = new HashSet<>();
        int inputSize = 0;
        for (Input input : inputs) {
            if (!keys.add(input.key())) {
                throw new IllegalArgumentException("duplicate dialog input key: " + input.key());
            }
            inputSize += input.type().equals("text") ? input.maxLength() : 256;
        }
        if (inputSize > 16000) {
            throw new IllegalArgumentException("dialog input length budget exceeds 16000 characters");
        }
    }

    @Override
    public MenuActionType getType() {
        return MenuActionType.DIALOG;
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new DialogMenuAction(this);
    }

    @Override
    public List<MenuActionData> nestedActions() {
        List<MenuActionData> actions = new ArrayList<>(unsupported);
        actions.addAll(onTimeout);
        for (Button button : buttons) {
            actions.addAll(button.actions());
        }
        if (exitButton != null) {
            actions.addAll(exitButton.actions());
        }
        return List.copyOf(actions);
    }

    private static int bounded(Integer value, int fallback, int minimum, int maximum, String field) {
        int resolved = value == null ? fallback : value;
        if (resolved < minimum || resolved > maximum) {
            throw new IllegalArgumentException("dialog " + field + " must be between " + minimum + " and " + maximum);
        }
        return resolved;
    }

    public record Body(String text, Integer width) {
        public Body {
            text = text == null ? "" : text;
            width = bounded(width, 200, 1, 1024, "body width");
        }
    }

    public record Button(String label, String tooltip, Integer width, List<MenuActionData> actions) {
        public Button {
            label = label == null ? "OK" : label;
            width = bounded(width, 150, 1, 1024, "button width");
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record Input(String key, String type, String label, Integer width, Boolean labelVisible,
                        JsonElement initial, Integer maxLength, Integer maxLines, Integer height,
                        List<Option> options, Float start, Float end, Float step, String labelFormat) {
        public Input {
            if (key == null || !key.matches("[A-Za-z0-9_]{1,64}")) {
                throw new IllegalArgumentException("dialog input key requires 1..64 letters, digits or underscores");
            }
            type = type == null ? "text" : type.toLowerCase(Locale.ROOT);
            if (!Set.of("text", "boolean", "single_option", "number_range").contains(type)) {
                throw new IllegalArgumentException("unsupported dialog input type: " + type);
            }
            label = label == null ? key : label;
            width = bounded(width, 200, 1, 1024, "input width");
            labelVisible = labelVisible == null || labelVisible;
            maxLength = bounded(maxLength, 32, 1, 4096, "input maxLength");
            if (maxLines != null) {
                maxLines = bounded(maxLines, 1, 1, 4096, "input maxLines");
            }
            if (height != null) {
                height = bounded(height, 1, 1, 512, "input height");
            }
            options = options == null ? List.of() : List.copyOf(options);
            initial = initial == null || initial.isJsonNull() ? null : initial.deepCopy();
            if (initial != null && !initial.isJsonPrimitive()) {
                throw new IllegalArgumentException("dialog input initial must be a scalar");
            }
            if (type.equals("text") && initial != null
                && (!initial.getAsJsonPrimitive().isString() || initial.getAsString().length() > maxLength)) {
                throw new IllegalArgumentException("dialog text initial must be a string within maxLength");
            }
            if (type.equals("boolean") && initial != null && !initial.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("dialog boolean initial must be a boolean");
            }
            if (type.equals("single_option")) {
                Set<String> ids = new HashSet<>();
                if (options.isEmpty() || options.size() > 64) {
                    throw new IllegalArgumentException("dialog single_option requires 1..64 options");
                }
                for (Option option : options) {
                    if (!ids.add(option.id())) {
                        throw new IllegalArgumentException("duplicate dialog option: " + option.id());
                    }
                }
                if (initial != null && (!initial.getAsJsonPrimitive().isString() || !ids.contains(initial.getAsString()))) {
                    throw new IllegalArgumentException("dialog single_option initial must name an option");
                }
            }
            if (type.equals("number_range")) {
                if (start == null || end == null || !Float.isFinite(start) || !Float.isFinite(end) || start.equals(end)) {
                    throw new IllegalArgumentException("dialog number_range requires distinct finite start and end");
                }
                if (step != null && (!Float.isFinite(step) || step <= 0)) {
                    throw new IllegalArgumentException("dialog number_range step must be finite and positive");
                }
                if (initial != null && (!initial.getAsJsonPrimitive().isNumber() || !Float.isFinite(initial.getAsFloat())
                    || initial.getAsFloat() < Math.min(start, end) || initial.getAsFloat() > Math.max(start, end))) {
                    throw new IllegalArgumentException("dialog number_range initial must be within its range");
                }
            }
        }
    }

    public record Option(String id, String label) {
        public Option {
            if (id == null || id.isEmpty() || id.length() > 256) {
                throw new IllegalArgumentException("dialog option id requires 1..256 characters");
            }
            label = label == null ? id : label;
        }
    }
}
