package art.arcane.gloss.doc;

import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.motd.MotdDoc;
import art.arcane.gloss.tab.TablistDoc;

import java.util.List;
import java.util.Objects;

public final class ShippedDocumentCatalog {
    public record Entry<T>(String kind, List<String> names, DocumentParser<T> parser) {
        public Entry {
            kind = Objects.requireNonNull(kind, "kind");
            names = List.copyOf(names);
            parser = Objects.requireNonNull(parser, "parser");
        }
    }

    public static final Entry<EmojiDoc> EMOJI = new Entry<>(EmojiDoc.KIND, List.of(
        "heart", "airplane", "mail", "cut", "pencil", "nib", "check", "thickcheck", "cross", "thickcross",
        "star", "darkstarstar", "snowflake", "sparkle", "sun", "left", "right", "up", "down", "leftright",
        "updown", "upleft", "upright", "downright", "downleft", "tapedrive", "thickplus", "lineplus",
        "opencross", "thickopencross", "maltcross", "starofdavid", "heavystar", "blackdiamond",
        "vbar1", "vbar2", "vbar3",
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
        "1t", "2t", "3t", "4t", "5t", "6t", "7t", "8t", "9t", "10t",
        "1h", "2h", "3h", "4h", "5h", "6h", "7h", "8h", "9h", "10h"), EmojiDoc::parse);

    public static final Entry<AnimationDoc> ANIMATIONS =
        new Entry<>(AnimationDoc.KIND, List.of(
            "rainbow", "marquee", "timeline", "typewriter", "flash", "wipe", "scanner", "decode",
            "odometer", "wave"), AnimationDoc::parse);

    public static final Entry<BoardDoc> BOARDS =
        new Entry<>(BoardDoc.KIND, List.of("default"), BoardDoc::parse);

    public static final Entry<BubbleStyleDoc> BUBBLES =
        new Entry<>(BubbleStyleDoc.KIND, List.of("default"), BubbleStyleDoc::parse);

    public static final Entry<TablistDoc> TABLIST =
        new Entry<>(TablistDoc.KIND, List.of("tablist"), TablistDoc::parse);

    public static final Entry<MotdDoc> MOTD =
        new Entry<>(MotdDoc.KIND, List.of("motd"), MotdDoc::parse);

    public static final Entry<RealDropSettingsDoc> REAL_DROPS =
        new Entry<>(RealDropSettingsDoc.KIND, List.of(RealDropSettingsDoc.DEFAULT_ID),
            RealDropSettingsDoc::parse);

    private ShippedDocumentCatalog() {
    }

    public static List<Entry<?>> all() {
        return List.of(EMOJI, ANIMATIONS, BOARDS, BUBBLES, TABLIST, MOTD, REAL_DROPS);
    }
}
