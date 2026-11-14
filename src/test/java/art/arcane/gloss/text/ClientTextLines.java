package art.arcane.gloss.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The lines of styled runs the vanilla client draws for a component.
 *
 * <p>Follows the client: children inherit their parent's style, section-sign codes left inside
 * literal text are read by {@code StringDecomposer.iterateFormatted} (a colour clears bold, italic,
 * underline, strikethrough and obfuscation, a format adds to the style, {@code §r} returns to the
 * component's own style, an unknown code is skipped with its marker), and a text display breaks
 * lines at {@code \n} without touching the style.
 */
public final class ClientTextLines {
    private static final int WHITE = 0xFFFFFF;

    private ClientTextLines() {
    }

    public record Run(String text, int rgb, boolean bold, boolean italic, boolean underlined,
               boolean strikethrough, boolean obfuscated) {
        public static Run plain(String text, int rgb) {
            return new Run(text, rgb, false, false, false, false, false);
        }

        public static Run bold(String text, int rgb) {
            return new Run(text, rgb, true, false, false, false, false);
        }

        public static Run italic(String text, int rgb) {
            return new Run(text, rgb, false, true, false, false, false);
        }

        boolean sameStyle(Run other) {
            return rgb == other.rgb && bold == other.bold && italic == other.italic
                && underlined == other.underlined && strikethrough == other.strikethrough
                && obfuscated == other.obfuscated;
        }
    }

    private record Resolved(int rgb, boolean bold, boolean italic, boolean underlined,
                            boolean strikethrough, boolean obfuscated) {
        static final Resolved BASE = new Resolved(WHITE, false, false, false, false, false);

        Resolved merge(Style style) {
            TextColor color = style.color();
            return new Resolved(
                color == null ? rgb : color.value(),
                pick(style, TextDecoration.BOLD, bold),
                pick(style, TextDecoration.ITALIC, italic),
                pick(style, TextDecoration.UNDERLINED, underlined),
                pick(style, TextDecoration.STRIKETHROUGH, strikethrough),
                pick(style, TextDecoration.OBFUSCATED, obfuscated));
        }

        Resolved legacy(char code) {
            return switch (code) {
                case 'k' -> new Resolved(rgb, bold, italic, underlined, strikethrough, true);
                case 'l' -> new Resolved(rgb, true, italic, underlined, strikethrough, obfuscated);
                case 'm' -> new Resolved(rgb, bold, italic, underlined, true, obfuscated);
                case 'n' -> new Resolved(rgb, bold, italic, true, strikethrough, obfuscated);
                case 'o' -> new Resolved(rgb, bold, true, underlined, strikethrough, obfuscated);
                default -> new Resolved(legacyColour(code), false, false, false, false, false);
            };
        }

        private static boolean pick(Style style, TextDecoration decoration, boolean inherited) {
            TextDecoration.State state = style.decoration(decoration);
            return state == TextDecoration.State.NOT_SET ? inherited : state == TextDecoration.State.TRUE;
        }
    }

    /** Lines of one text display, or any single component the client splits at newlines. */
    public static List<List<Run>> lines(Component component) {
        return lines(component, Resolved.BASE);
    }

    /**
     * A custom item name in the tooltip: {@code ItemStack.getStyledHoverName} wraps it in italic and
     * the rarity colour, white for a common item.
     */
    public static List<List<Run>> customName(Component name) {
        return lines(name, new Resolved(WHITE, false, true, false, false, false));
    }

    /** Lore lines in the tooltip: {@code ItemLore} merges each one over dark purple italic. */
    public static List<List<Run>> loreLines(List<Component> lore) {
        Resolved loreStyle = new Resolved(NamedTextColor.DARK_PURPLE.value(), false, true, false, false, false);
        List<List<Run>> lines = new ArrayList<>();
        for (Component line : lore) {
            lines.addAll(lines(line, loreStyle));
        }
        return lines;
    }

    private static List<List<Run>> lines(Component component, Resolved base) {
        List<List<Run>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        visit(component, base, lines);
        return lines;
    }

    /** One line per component, each starting from the client's default style. */
    public static List<List<Run>> entityLines(List<Component> components) {
        List<List<Run>> lines = new ArrayList<>();
        for (Component component : components) {
            lines.addAll(lines(component));
        }
        return lines;
    }

    private static void visit(Component component, Resolved inherited, List<List<Run>> lines) {
        if (!(component instanceof TextComponent text)) {
            throw new IllegalArgumentException("only text components are modelled: " + component);
        }
        Resolved own = inherited.merge(component.style());
        literal(text.content(), own, lines);
        for (Component child : component.children()) {
            visit(child, own, lines);
        }
    }

    private static void literal(String content, Resolved own, List<List<Run>> lines) {
        Resolved style = own;
        StringBuilder run = new StringBuilder();
        for (int index = 0; index < content.length(); index++) {
            char value = content.charAt(index);
            if (value == '§') {
                if (index + 1 >= content.length()) {
                    break;
                }
                append(lines, run, style);
                char code = Character.toLowerCase(content.charAt(index + 1));
                if (code == 'r') {
                    style = own;
                } else if ("0123456789abcdefklmno".indexOf(code) >= 0) {
                    style = style.legacy(code);
                }
                index++;
                continue;
            }
            if (value == '\n') {
                append(lines, run, style);
                lines.add(new ArrayList<>());
                continue;
            }
            run.append(value);
        }
        append(lines, run, style);
    }

    private static void append(List<List<Run>> lines, StringBuilder run, Resolved style) {
        if (run.isEmpty()) {
            return;
        }
        List<Run> line = lines.get(lines.size() - 1);
        Run next = new Run(run.toString(), style.rgb, style.bold, style.italic, style.underlined,
            style.strikethrough, style.obfuscated);
        Run last = line.isEmpty() ? null : line.get(line.size() - 1);
        if (last != null && last.sameStyle(next)) {
            line.set(line.size() - 1, new Run(last.text + next.text, last.rgb, last.bold, last.italic,
                last.underlined, last.strikethrough, last.obfuscated));
        } else {
            line.add(next);
        }
        run.setLength(0);
    }

    private static int legacyColour(char code) {
        NamedTextColor color = switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> throw new IllegalArgumentException(String.format(Locale.ROOT, "not a colour code: %s", code));
        };
        return color.value();
    }
}
