package art.arcane.gloss.indicator;

import art.arcane.gloss.service.AdmissionBudget;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class IndicatorSampleWindow {
    private final Map<UUID, AdmissionBudget.Lease> pending = new ConcurrentHashMap<>();
    private final AdmissionBudget budget = new AdmissionBudget(16384);

    AdmissionBudget.Lease begin(UUID entity, int limit) {
        if (pending.containsKey(entity)) {
            return null;
        }
        AdmissionBudget.Lease lease = budget.tryAcquire(limit);
        if (lease == null) {
            return null;
        }
        if (pending.putIfAbsent(entity, lease) != null) {
            lease.close();
            return null;
        }
        return lease;
    }

    boolean finish(UUID entity, AdmissionBudget.Lease lease) {
        boolean current = pending.remove(entity, lease);
        lease.close();
        return current;
    }

    void clear() {
        for (Map.Entry<UUID, AdmissionBudget.Lease> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
            }
        }
    }
}
