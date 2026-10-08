package art.arcane.gloss.inventory;

public record InventoryRefreshPolicy(String mode, Integer titleTicks, Integer slotsTicks,
                                     Integer conditionsTicks, Integer listTicks) {
    public static final InventoryRefreshPolicy DEFAULT = new InventoryRefreshPolicy(null, null, null, null, null);

    public InventoryRefreshPolicy {
        mode = mode == null ? "dynamic" : mode;
        if (!mode.equals("dynamic") && !mode.equals("always")) {
            throw new IllegalArgumentException("inventory refresh mode must be dynamic or always");
        }
        titleTicks = ticks(titleTicks, 20);
        slotsTicks = ticks(slotsTicks, 20);
        conditionsTicks = ticks(conditionsTicks, 20);
        if (listTicks != null) {
            listTicks = ticks(listTicks, 20);
        }
    }

    public int resolvedListTicks(InventoryDoc.ListSection list) {
        return listTicks == null ? list == null ? 0 : list.refreshTicks() : listTicks;
    }

    private static int ticks(Integer value, int fallback) {
        int ticks = value == null ? fallback : value;
        if (ticks < 0 || ticks > 1200) {
            throw new IllegalArgumentException("inventory refresh ticks must be between 0 and 1200");
        }
        return ticks;
    }
}
