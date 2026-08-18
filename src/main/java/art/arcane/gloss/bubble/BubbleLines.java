package art.arcane.gloss.bubble;

import art.arcane.volmlib.util.format.Form;

import java.util.ArrayList;
import java.util.List;

public final class BubbleLines {
    private BubbleLines() {
    }

    public static List<String> split(String message, int wrapChars) {
        if (message == null) {
            return List.of();
        }

        String stripped = stripColors(message);
        String[] parts = Form.wrapWords(stripped, wrapChars).split("\n");
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isBlank()) {
                lines.add(part);
            }
        }
        return lines;
    }

    public static String stripColors(String message) {
        StringBuilder builder = new StringBuilder(message.length());
        int index = 0;
        while (index < message.length()) {
            char current = message.charAt(index);
            if (current == '§' && index + 1 < message.length()) {
                index += 2;
                continue;
            }
            builder.append(current);
            index++;
        }
        return builder.toString();
    }
}
