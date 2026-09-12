package art.arcane.gloss.dialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One control in a dialog. The four protocol controls share this record because the client sends
 * their answers back under one flat key namespace; the compact constructor keeps each type to the
 * components that mean something for it, so a number control can never ship a {@code maxLength}
 * the client will drop.
 */
public record DialogInput(String type, String key, String label, Boolean labelVisible, Integer width,
                          String initial, Integer maxLength, Multiline multiline,
                          Float start, Float end, Float step, Float initialNumber,
                          String labelFormat, List<Option> options, String onTrue, String onFalse) {
    public static final String TEXT = "text";
    public static final String NUMBER = "number";
    public static final String OPTION = "option";
    public static final String BOOL = "bool";
    public static final int DEFAULT_WIDTH = 200;
    public static final int DEFAULT_MAX_LENGTH = 32;
    public static final int MAX_MAX_LENGTH = 32767;
    public static final String DEFAULT_LABEL_FORMAT = "options.generic_value";

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

    public DialogInput {
        type = type == null ? TEXT : type.trim();
        key = key == null ? "" : key.trim();
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("dialog input key must match [a-z][a-z0-9_]*: " + key);
        }
        label = label == null ? "" : label;
        labelVisible = labelVisible == null || labelVisible;
        width = DialogWidths.clamp(width, DEFAULT_WIDTH);
        switch (type) {
            case TEXT -> {
                initial = initial == null ? "" : initial;
                maxLength = maxLength == null ? DEFAULT_MAX_LENGTH : Math.clamp(maxLength.intValue(), 1, MAX_MAX_LENGTH);
                if (initial.length() > maxLength) {
                    throw new IllegalArgumentException("dialog input " + key + " initial text is longer than maxLength");
                }
                start = null;
                end = null;
                step = null;
                initialNumber = null;
                labelFormat = null;
                options = List.of();
                onTrue = null;
                onFalse = null;
            }
            case NUMBER -> {
                if (start == null || end == null || !Float.isFinite(start) || !Float.isFinite(end)) {
                    throw new IllegalArgumentException("dialog input " + key + " requires a finite start and end");
                }
                if (start >= end) {
                    throw new IllegalArgumentException("dialog input " + key + " requires start < end");
                }
                initialNumber = initialNumber == null ? parseFloat(initial) : initialNumber;
                if (initialNumber != null && (initialNumber < start || initialNumber > end)) {
                    throw new IllegalArgumentException("dialog input " + key + " initial value is outside start..end");
                }
                if (step != null && (!Float.isFinite(step) || step <= 0F)) {
                    throw new IllegalArgumentException("dialog input " + key + " step must be greater than zero");
                }
                labelFormat = labelFormat == null || labelFormat.isBlank() ? DEFAULT_LABEL_FORMAT : labelFormat.trim();
                initial = null;
                maxLength = null;
                multiline = null;
                options = List.of();
                onTrue = null;
                onFalse = null;
            }
            case OPTION -> {
                options = copyOptions(key, options);
                initial = null;
                maxLength = null;
                multiline = null;
                start = null;
                end = null;
                step = null;
                initialNumber = null;
                labelFormat = null;
                onTrue = null;
                onFalse = null;
            }
            case BOOL -> {
                initial = String.valueOf(Boolean.parseBoolean(initial));
                onTrue = onTrue == null || onTrue.isEmpty() ? "true" : onTrue;
                onFalse = onFalse == null || onFalse.isEmpty() ? "false" : onFalse;
                maxLength = null;
                multiline = null;
                start = null;
                end = null;
                step = null;
                initialNumber = null;
                labelFormat = null;
                options = List.of();
            }
            default -> throw new IllegalArgumentException(
                "dialog input type must be text, number, option or bool: " + type);
        }
    }

    public boolean initialBoolean() {
        return Boolean.parseBoolean(initial);
    }

    private static Float parseFloat(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Float.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("dialog number input initial value is not a number: " + raw);
        }
    }

    private static List<Option> copyOptions(String key, List<Option> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("dialog input " + key + " requires at least one option");
        }
        List<Option> copied = new ArrayList<>(options.size());
        Set<String> ids = new HashSet<>(options.size());
        boolean initialSeen = false;
        for (Option option : options) {
            if (option == null) {
                throw new IllegalArgumentException("dialog input " + key + " options may not contain null entries");
            }
            if (!ids.add(option.id())) {
                throw new IllegalArgumentException("dialog input " + key + " duplicates option id " + option.id());
            }
            if (option.initial()) {
                if (initialSeen) {
                    throw new IllegalArgumentException("dialog input " + key + " declares more than one initial option");
                }
                initialSeen = true;
            }
            copied.add(option);
        }
        return List.copyOf(copied);
    }

    /** Multi-line geometry for a text control; both components are optional to the client. */
    public record Multiline(Integer maxLines, Integer height) {
        public Multiline {
            if (maxLines != null && maxLines < 1) {
                throw new IllegalArgumentException("dialog input maxLines must be at least 1");
            }
            if (height != null) {
                height = Math.clamp(height.intValue(), 1, DialogWidths.MAXIMUM);
            }
        }
    }

    /** One entry of an option control; {@code id} is what the client sends back. */
    public record Option(String id, String label, boolean initial) {
        public Option {
            id = id == null ? "" : id.trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("dialog option requires an id");
            }
        }
    }
}
