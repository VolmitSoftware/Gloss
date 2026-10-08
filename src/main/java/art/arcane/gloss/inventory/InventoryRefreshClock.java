package art.arcane.gloss.inventory;

final class InventoryRefreshClock {
    private final InventoryRefreshPlan plan;
    private volatile long title;
    private volatile long slots;
    private volatile long conditions;
    private volatile long list;

    InventoryRefreshClock(InventoryRefreshPlan plan, long now) {
        this.plan = plan;
        rendered(InventoryRefreshPlan.ALL, now);
    }

    int due(long now) {
        int due = 0;
        if (plan.titleTicks() > 0 && now - title >= plan.titleTicks()) {
            due |= InventoryRefreshPlan.TITLE;
        }
        if (plan.slotsTicks() > 0 && now - slots >= plan.slotsTicks()) {
            due |= InventoryRefreshPlan.SLOTS;
        }
        if (plan.conditionsTicks() > 0 && now - conditions >= plan.conditionsTicks()) {
            due |= InventoryRefreshPlan.CONDITIONS;
        }
        if (plan.listTicks() > 0 && now - list >= plan.listTicks()) {
            due |= InventoryRefreshPlan.LIST;
        }
        return due;
    }

    void rendered(int mask, long now) {
        if ((mask & InventoryRefreshPlan.TITLE) != 0) {
            title = now;
        }
        if ((mask & InventoryRefreshPlan.SLOTS) != 0) {
            slots = now;
        }
        if ((mask & InventoryRefreshPlan.CONDITIONS) != 0) {
            conditions = now;
        }
        if ((mask & InventoryRefreshPlan.LIST) != 0) {
            list = now;
        }
    }
}
