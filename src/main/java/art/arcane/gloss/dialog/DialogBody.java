package art.arcane.gloss.dialog;

import art.arcane.gloss.config.icon.MenuIconData;

/**
 * One block of a dialog's body: a paragraph of text, or an item with an optional description
 * beside it. The protocol has no other body kinds, so an unknown {@code type} is refused at load.
 */
public record DialogBody(String type, String text, Integer width, MenuIconData item, String description,
                         Boolean showTooltip, Boolean showDecorations, Integer height) {
    public static final String TEXT = "text";
    public static final String ITEM = "item";
    public static final int DEFAULT_TEXT_WIDTH = 200;
    public static final int DEFAULT_ITEM_SIZE = 16;

    public DialogBody {
        type = type == null ? TEXT : type.trim();
        if (!type.equals(TEXT) && !type.equals(ITEM)) {
            throw new IllegalArgumentException("dialog body type must be text or item: " + type);
        }
        if (type.equals(TEXT)) {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("dialog text body requires text");
            }
            width = DialogWidths.clamp(width, DEFAULT_TEXT_WIDTH);
            height = null;
        } else {
            if (item == null) {
                throw new IllegalArgumentException("dialog item body requires an item icon");
            }
            width = DialogWidths.clamp(width, DEFAULT_ITEM_SIZE);
            height = DialogWidths.clamp(height, DEFAULT_ITEM_SIZE);
        }
    }

    public boolean isItem() {
        return type.equals(ITEM);
    }

    public boolean showTooltipOrDefault() {
        return showTooltip == null || showTooltip;
    }

    public boolean showDecorationsOrDefault() {
        return showDecorations == null || showDecorations;
    }
}
