package art.arcane.gloss.dialog;

import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.ConfirmationDialog;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.DialogListDialog;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog;
import com.github.retrooper.packetevents.protocol.dialog.ServerLinksDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.body.ItemDialogBody;
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
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.mapper.MappedEntityRefSet;
import com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a rendered dialog into the protocol types packetevents writes. Every button carries a
 * {@code gloss:dialog} dynamic custom action whose payload names the document, the click index and
 * the token that authorizes the response, so a stale screen the client kept open answers into a
 * token nobody is waiting for.
 */
public final class DialogEncoder {
    public static final String ACTION_NAMESPACE = "gloss";
    public static final String ACTION_KEY = "dialog";
    public static final String PAYLOAD_DOCUMENT = "doc";
    public static final String PAYLOAD_BUTTON = "button";
    public static final String PAYLOAD_TOKEN = "token";

    private static final ResourceLocation ACTION_ID = new ResourceLocation(ACTION_NAMESPACE, ACTION_KEY);

    private DialogEncoder() {
    }

    public static Dialog encode(DialogRuntime.Rendered rendered, String token) {
        CommonDialogData common = common(rendered);
        return switch (rendered.type()) {
            case NOTICE -> new NoticeDialog(common, rendered.buttons().isEmpty()
                ? NoticeDialog.DEFAULT_ACTION
                : button(rendered, rendered.buttons().getFirst(), token));
            case CONFIRMATION -> new ConfirmationDialog(common,
                button(rendered, rendered.buttons().getFirst(), token),
                button(rendered, rendered.buttons().get(1), token));
            case MULTI_ACTION -> new MultiActionDialog(common, buttons(rendered, token),
                exit(rendered, token), rendered.columns());
            case SERVER_LINKS -> new ServerLinksDialog(common, exit(rendered, token),
                rendered.columns(), rendered.buttonWidth());
            case DIALOG_LIST -> new DialogListDialog(common, dialogs(rendered), exit(rendered, token),
                rendered.columns(), rendered.buttonWidth());
        };
    }

    private static CommonDialogData common(DialogRuntime.Rendered rendered) {
        List<com.github.retrooper.packetevents.protocol.dialog.body.DialogBody> body =
            new ArrayList<>(rendered.body().size());
        for (DialogRuntime.Rendered.Body block : rendered.body()) {
            if (!block.item()) {
                body.add(new PlainMessageDialogBody(new PlainMessage(text(block.text()), block.width())));
                continue;
            }
            ItemStack item = item(block);
            if (item == null) {
                continue;
            }
            PlainMessage description = block.description() == null || block.description().isEmpty()
                ? null
                : new PlainMessage(text(block.description()), block.width());
            body.add(new ItemDialogBody(item, description, block.showDecorations(), block.showTooltip(),
                block.width(), block.height()));
        }
        List<Input> inputs = new ArrayList<>(rendered.inputs().size());
        for (DialogRuntime.Rendered.Input input : rendered.inputs()) {
            inputs.add(new Input(input.source().key(), control(input)));
        }
        return new CommonDialogData(text(rendered.title()),
            rendered.externalTitle() == null ? null : text(rendered.externalTitle()),
            rendered.canCloseWithEscape(), rendered.pause(), afterAction(rendered.afterAction()),
            List.copyOf(body), List.copyOf(inputs));
    }

    private static ItemStack item(DialogRuntime.Rendered.Body block) {
        if (block.itemStack() == null) {
            return null;
        }
        return SpigotConversionUtil.fromBukkitItemStack(block.itemStack());
    }

    private static InputControl control(DialogRuntime.Rendered.Input input) {
        DialogInput source = input.source();
        Component label = text(input.label());
        return switch (source.type()) {
            case DialogInput.NUMBER -> new NumberRangeInputControl(source.width(), label, source.labelFormat(),
                new NumberRangeInputControl.RangeInfo(source.start(), source.end(), source.initialNumber(), source.step()));
            case DialogInput.BOOL -> new BooleanInputControl(label, source.initialBoolean(),
                source.onTrue(), source.onFalse());
            case DialogInput.OPTION -> new SingleOptionInputControl(source.width(), options(source),
                label, source.labelVisible());
            default -> new TextInputControl(source.width(), label, source.labelVisible(), source.initial(),
                source.maxLength(), multiline(source));
        };
    }

    private static List<SingleOptionInputControl.Entry> options(DialogInput source) {
        List<SingleOptionInputControl.Entry> entries = new ArrayList<>(source.options().size());
        for (DialogInput.Option option : source.options()) {
            entries.add(new SingleOptionInputControl.Entry(option.id(),
                option.label() == null || option.label().isEmpty() ? null : text(option.label()),
                option.initial()));
        }
        return List.copyOf(entries);
    }

    private static TextInputControl.MultilineOptions multiline(DialogInput source) {
        DialogInput.Multiline multiline = source.multiline();
        return multiline == null ? null : new TextInputControl.MultilineOptions(multiline.maxLines(), multiline.height());
    }

    private static List<ActionButton> buttons(DialogRuntime.Rendered rendered, String token) {
        List<ActionButton> encoded = new ArrayList<>(rendered.buttons().size());
        for (DialogRuntime.Rendered.Button button : rendered.buttons()) {
            encoded.add(button(rendered, button, token));
        }
        return List.copyOf(encoded);
    }

    private static ActionButton exit(DialogRuntime.Rendered rendered, String token) {
        return rendered.exit() == null ? null : button(rendered, rendered.exit(), token);
    }

    private static ActionButton button(DialogRuntime.Rendered rendered, DialogRuntime.Rendered.Button button,
                                       String token) {
        CommonButtonData data = new CommonButtonData(text(button.label()),
            button.tooltip() == null || button.tooltip().isEmpty() ? null : text(button.tooltip()),
            button.width());
        return new ActionButton(data, new DynamicCustomAction(ACTION_ID, payload(rendered.id(), button.index(), token)));
    }

    /** The compound the client echoes back with the response; {@link DialogResponseListener} reads it. */
    static NBTCompound payload(String documentId, int buttonIndex, String token) {
        NBTCompound payload = new NBTCompound();
        payload.setTag(PAYLOAD_DOCUMENT, new NBTString(documentId));
        payload.setTag(PAYLOAD_BUTTON, new NBTInt(buttonIndex));
        payload.setTag(PAYLOAD_TOKEN, new NBTString(token));
        return payload;
    }

    private static MappedEntityRefSet<Dialog> dialogs(DialogRuntime.Rendered rendered) {
        NBTList<NBTString> list = NBTList.createStringList();
        for (String id : rendered.dialogs()) {
            list.addTag(new NBTString(id));
        }
        PacketWrapper<?> wrapper = PacketWrapper.createDummyWrapper(
            PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());
        return MappedEntitySet.decodeRefSet(list, wrapper);
    }

    private static DialogAction afterAction(String afterAction) {
        return switch (afterAction) {
            case DialogDoc.AFTER_NONE -> DialogAction.NONE;
            case DialogDoc.AFTER_WAIT -> DialogAction.WAIT_FOR_RESPONSE;
            default -> DialogAction.CLOSE;
        };
    }

    private static Component text(String raw) {
        return TextUtils.parse(raw == null ? "" : raw);
    }
}
