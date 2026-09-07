package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconDisplayStyle;
import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public record HologramBoxLayout(int textWidth, int textHeight, int panelWidth, int panelHeight,
                         int frameWidth, int frameHeight) {
    private static final float PIXEL = 0.025F;

    /**
     * The projection of a string that {@link #measure} actually reads: glyphs, line breaks and the
     * bold state. Colour codes only reset bold, so an animated gradient produces a stable key and
     * the box does not re-measure and reconfigure its parts on every frame.
     */
    public static String layoutKey(String text) {
        StringBuilder key = null;
        for (int index = 0; index < text.length();) {
            char value = text.charAt(index);
            if (value != '\u00a7' || index + 1 >= text.length()) {
                if (key != null) {
                    key.append(value);
                }
                index++;
                continue;
            }
            char code = Character.toLowerCase(text.charAt(index + 1));
            boolean reset = "0123456789abcdefrx".indexOf(code) >= 0;
            int width = code == 'x' && index + 13 < text.length() ? 14 : 2;
            if (key == null) {
                key = new StringBuilder(text.length());
                key.append(text, 0, index);
            }
            if (code == 'l') {
                key.append('\u00a7').append('l');
            } else if (reset) {
                key.append('\u00a7').append('r');
            }
            index += width;
        }

        return key == null ? text : key.toString();
    }

    public static HologramBoxLayout measure(String text, int lineWidth, HologramBox box) {
        int maximum = 0;
        int width = 0;
        int lines = 1;
        int breakWidth = -1;
        int afterBreakWidth = 0;
        boolean bold = false;
        for (int index = 0; index < text.length();) {
            char value = text.charAt(index);
            if (value == '\u00a7' && index + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(index + 1));
                if (code == 'l') {
                    bold = true;
                } else if ("0123456789abcdefrx".indexOf(code) >= 0) {
                    bold = false;
                }
                index += code == 'x' && index + 13 < text.length() ? 14 : 2;
                continue;
            }
            if (value == '\n' || value == '\r' || value == '\u2028' || value == '\u2029') {
                maximum = Math.max(maximum, width);
                width = 0;
                breakWidth = -1;
                lines++;
                index += value == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n' ? 2 : 1;
                continue;
            }
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            MapFont.CharacterSprite glyph = codePoint <= Character.MAX_VALUE
                ? MinecraftFont.Font.getChar((char) codePoint) : null;
            int advance = (glyph == null ? 5 : glyph.getWidth()) + 1 + (bold ? 1 : 0);
            if (width > 0 && width + advance > lineWidth) {
                if (breakWidth >= 0) {
                    maximum = Math.max(maximum, breakWidth);
                    width -= afterBreakWidth;
                } else {
                    maximum = Math.max(maximum, width);
                    width = 0;
                }
                breakWidth = -1;
                lines++;
                if (codePoint == ' ') {
                    continue;
                }
            }
            if (codePoint == ' ') {
                breakWidth = width;
                afterBreakWidth = width + advance;
            }
            width += advance;
        }
        int textWidth = Math.max(maximum, width) + 2;
        int textHeight = lines * 9 + 1;
        int panelWidth = textWidth + box.padding() * 2;
        int panelHeight = textHeight + box.padding() * 2;
        return new HologramBoxLayout(textWidth, textHeight, panelWidth, panelHeight,
            panelWidth + box.borderWidth() * 2, panelHeight + box.borderWidth() * 2);
    }

    public List<Part> parts(HologramBox box) {
        List<Part> parts = new ArrayList<>(5);
        if ((box.backgroundArgb().argb() >>> 24) > 0) {
            parts.add(new Part(0F, 0F, panelWidth, panelHeight, box.backgroundArgb().argb()));
        }
        int border = box.borderWidth();
        if (border > 0 && (box.borderArgb().argb() >>> 24) > 0) {
            int color = box.borderArgb().argb();
            parts.add(new Part(0F, (panelHeight + border) / 2F, frameWidth, border, color));
            parts.add(new Part(0F, -(panelHeight + border) / 2F, frameWidth, border, color));
            parts.add(new Part(-(panelWidth + border) / 2F, 0F, border, panelHeight, color));
            parts.add(new Part((panelWidth + border) / 2F, 0F, border, panelHeight, color));
        }
        return List.copyOf(parts);
    }

    public Transformation transform(Part part, HologramPresentation presentation, IconDisplayStyle style) {
        float width = part.width();
        float height = part.height();
        Vector3f parentScale = TextDisplayStyle.scale(presentation, style);
        Quaternionf rotation = TextDisplayStyle.rotation(presentation);
        Vector3f translation = new Vector3f((0.5F + part.x() - width / 10F) * PIXEL,
            (textHeight / 2F + part.y() - height / 2F) * PIXEL, 0.002F)
            .mul(parentScale);
        rotation.transform(translation);
        Vector3f scale = new Vector3f(width / 5F, height / 10F, 1F).mul(parentScale);
        return new Transformation(translation, rotation, scale, new Quaternionf());
    }

    public record Part(float x, float y, int width, int height, int color) {
    }
}
