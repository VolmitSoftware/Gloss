package art.arcane.gloss.rig;

import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.config.icon.MenuIconData;

import java.util.Objects;

public record Part(
    String id,
    String bone,
    PartType type,
    String block,
    MenuIconData item,
    String text,
    Transform local,
    String billboard,
    Integer brightness,
    IconDisplayStyle style
) {
    public Part {
        id = Objects.requireNonNull(id, "part id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("part id must not be blank");
        }
        bone = Objects.requireNonNull(bone, "part " + id + " needs a bone").trim();
        type = Objects.requireNonNull(type, "part " + id + " needs a type");
        local = local == null ? Transform.identity() : local;
        switch (type) {
            case BLOCK -> {
                if (block == null || block.isBlank()) {
                    throw new IllegalArgumentException("block part " + id + " needs a block");
                }
            }
            case ITEM -> {
                if (item == null) {
                    throw new IllegalArgumentException("item part " + id + " needs an item");
                }
            }
            case TEXT -> {
                if (text == null) {
                    throw new IllegalArgumentException("text part " + id + " needs text");
                }
            }
        }
    }
}
