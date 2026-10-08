package art.arcane.gloss.prompt;

import art.arcane.gloss.config.action.DialogActionData;
import art.arcane.gloss.menu.MenuExpressions;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.ConfirmationDialog;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.body.DialogBody;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.Input;
import com.github.retrooper.packetevents.protocol.dialog.input.InputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.NumberRangeInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.SingleOptionInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTFloat;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DialogDefinition {
    private final DialogActionData data;
    private final List<List<MenuAction<?>>> actions;
    private final List<MenuAction<?>> unsupported;
    private final List<MenuAction<?>> timeout;

    public DialogDefinition(DialogActionData data) {
        this.data = data;
        List<List<MenuAction<?>>> compiled = new ArrayList<>(data.buttons().size() + 1);
        for (DialogActionData.Button button : data.buttons()) {
            compiled.add(MenuAction.resolve(button.actions(), "dialog", button.label()));
        }
        if (data.exitButton() != null) {
            compiled.add(MenuAction.resolve(data.exitButton().actions(), "dialog", "exit"));
        }
        this.actions = List.copyOf(compiled);
        this.unsupported = MenuAction.resolve(data.unsupported(), "dialog", "unsupported");
        this.timeout = MenuAction.resolve(data.onTimeout(), "dialog", "timeout");
    }

    public Dialog create(ActionContext context, String nonce) {
        List<DialogBody> body = new ArrayList<>(data.body().size());
        for (DialogActionData.Body entry : data.body()) {
            body.add(new PlainMessageDialogBody(new PlainMessage(text(context, entry.text()), entry.width())));
        }
        List<Input> inputs = new ArrayList<>(data.inputs().size());
        for (DialogActionData.Input entry : data.inputs()) {
            inputs.add(new Input(entry.key(), control(context, entry)));
        }
        CommonDialogData common = new CommonDialogData(text(context, data.title()), null,
            data.escape(), false, DialogAction.CLOSE, body, inputs);
        List<ActionButton> buttons = new ArrayList<>(data.buttons().size());
        for (int index = 0; index < data.buttons().size(); index++) {
            buttons.add(button(context, data.buttons().get(index), nonce, index));
        }
        return switch (data.kind()) {
            case "confirmation" -> new ConfirmationDialog(common, buttons.get(0), buttons.get(1));
            case "multi_action" -> new MultiActionDialog(common, buttons,
                data.exitButton() == null ? null : button(context, data.exitButton(), nonce, buttons.size()), data.columns());
            default -> new NoticeDialog(common, buttons.getFirst());
        };
    }

    public int timeoutTicks() {
        return data.timeoutTicks();
    }

    public List<MenuAction<?>> actions(int button) {
        return actions.get(button);
    }

    public int buttonCount() {
        return actions.size();
    }

    public List<MenuAction<?>> unsupported() {
        return unsupported;
    }

    public List<MenuAction<?>> timeout() {
        return timeout;
    }

    public Map<String, Object> validate(NBT payload) {
        if (payload == null && data.inputs().isEmpty()) {
            return Map.of();
        }
        if (!(payload instanceof NBTCompound values) || values.size() != data.inputs().size()) {
            throw new IllegalArgumentException("dialog response must contain exactly the declared input keys");
        }
        Map<String, Object> result = new LinkedHashMap<>(data.inputs().size());
        for (DialogActionData.Input input : data.inputs()) {
            NBT value = values.getTagOrNull(input.key());
            result.put(input.key(), validate(input, value));
        }
        return Map.copyOf(result);
    }

    private static Object validate(DialogActionData.Input input, NBT value) {
        return switch (input.type()) {
            case "boolean" -> {
                if (!(value instanceof NBTByte flag) || flag.getAsByte() < 0 || flag.getAsByte() > 1) {
                    throw invalid(input);
                }
                yield flag.getAsByte() == 1;
            }
            case "number_range" -> {
                if (!(value instanceof NBTFloat number)) {
                    throw invalid(input);
                }
                double candidate = number.getAsDouble();
                if (!Double.isFinite(candidate) || candidate < Math.min(input.start(), input.end())
                    || candidate > Math.max(input.start(), input.end())) {
                    throw invalid(input);
                }
                if (input.step() != null) {
                    double initial = input.initial() == null
                        ? ((double) input.start() + input.end()) / 2.0 : input.initial().getAsDouble();
                    double steps = (candidate - initial) / input.step();
                    double tolerance = Math.max(0.00001, Math.ulp((float) candidate) * 2.0 / input.step());
                    if (Math.abs(steps - Math.rint(steps)) > tolerance) {
                        throw invalid(input);
                    }
                }
                yield candidate;
            }
            case "single_option" -> {
                if (!(value instanceof NBTString string)) {
                    throw invalid(input);
                }
                boolean allowed = false;
                for (DialogActionData.Option option : input.options()) {
                    if (option.id().equals(string.getValue())) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    throw invalid(input);
                }
                yield string.getValue();
            }
            default -> {
                if (!(value instanceof NBTString string) || string.getValue().length() > input.maxLength()
                    || string.getValue().indexOf('\0') >= 0) {
                    throw invalid(input);
                }
                String text = string.getValue();
                if (input.maxLines() == null && input.height() == null && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)) {
                    throw invalid(input);
                }
                if (input.maxLines() != null && text.split("\\R", -1).length > input.maxLines()) {
                    throw invalid(input);
                }
                yield text;
            }
        };
    }

    private static IllegalArgumentException invalid(DialogActionData.Input input) {
        return new IllegalArgumentException("invalid dialog response for input " + input.key());
    }

    private static InputControl control(ActionContext context, DialogActionData.Input input) {
        Component label = text(context, input.label());
        return switch (input.type()) {
            case "boolean" -> new BooleanInputControl(label,
                input.initial() != null && input.initial().getAsBoolean(), "true", "false");
            case "single_option" -> {
                List<SingleOptionInputControl.Entry> options = new ArrayList<>(input.options().size());
                for (DialogActionData.Option option : input.options()) {
                    options.add(new SingleOptionInputControl.Entry(option.id(), text(context, option.label()),
                        input.initial() != null && option.id().equals(input.initial().getAsString())));
                }
                yield new SingleOptionInputControl(input.width(), options, label, input.labelVisible());
            }
            case "number_range" -> new NumberRangeInputControl(input.width(), label,
                input.labelFormat() == null ? "options.generic_value" : input.labelFormat(),
                new NumberRangeInputControl.RangeInfo(input.start(), input.end(),
                    input.initial() == null ? null : input.initial().getAsFloat(), input.step()));
            default -> new TextInputControl(input.width(), label, input.labelVisible(),
                input.initial() == null ? "" : input.initial().getAsString(), input.maxLength(),
                input.height() == null && input.maxLines() == null ? null
                    : new TextInputControl.MultilineOptions(input.maxLines(), input.height()));
        };
    }

    private static ActionButton button(ActionContext context, DialogActionData.Button button, String nonce, int index) {
        return new ActionButton(new CommonButtonData(text(context, button.label()),
            button.tooltip() == null ? null : text(context, button.tooltip()), button.width()),
            new DynamicCustomAction(new ResourceLocation("gloss", "dialog/" + nonce + "/" + index), null));
    }

    private static Component text(ActionContext context, String value) {
        String scoped = value.contains("{{") ? MenuExpressions.substitute(value, context.conditionScope()) : value;
        return TextUtils.parse(TextPipeline.menuText(context.player(), scoped));
    }
}
