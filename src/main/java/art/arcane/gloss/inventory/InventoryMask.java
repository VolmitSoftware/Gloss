package art.arcane.gloss.inventory;

import art.arcane.gloss.config.components.ComponentData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a picture of a window into slot numbers. Mask rows read left to right, top to bottom, one
 * character per slot; a character with no key behind it is a deliberately empty slot, and explicit
 * {@code slots} entries win over whatever the mask drew there.
 */
public final class InventoryMask {
    /** A mask cell that is always empty, whatever the keys say. */
    public static final char EMPTY = ' ';

    private InventoryMask() {
    }

    /** @return every filled slot of the window, keyed by slot index */
    public static Map<Integer, ComponentData> resolve(int width, List<String> mask,
                                                      Map<String, ComponentData> keys,
                                                      Map<String, ComponentData> slots) {
        Map<Integer, ComponentData> resolved = new LinkedHashMap<>();
        for (int row = 0; row < mask.size(); row++) {
            String line = mask.get(row);
            for (int column = 0; column < line.length(); column++) {
                char cell = line.charAt(column);
                if (cell == EMPTY) {
                    continue;
                }
                ComponentData component = keys.get(String.valueOf(cell));
                if (component != null) {
                    resolved.put(row * width + column, component);
                }
            }
        }
        for (Map.Entry<String, ComponentData> entry : slots.entrySet()) {
            resolved.put(Integer.parseInt(entry.getKey().trim()), entry.getValue());
        }
        return Map.copyOf(resolved);
    }

    /** @return the slots the given mask character covers, in reading order */
    public static List<Integer> areaSlots(int width, List<String> mask, char area) {
        List<Integer> slots = new ArrayList<>();
        for (int row = 0; row < mask.size(); row++) {
            String line = mask.get(row);
            for (int column = 0; column < line.length(); column++) {
                if (line.charAt(column) == area) {
                    slots.add(row * width + column);
                }
            }
        }
        return List.copyOf(slots);
    }
}
