package art.arcane.gloss.integrate;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.integration.IntegrationServiceContract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ContractDiscovery {
    private final Set<String> warned;

    public record Candidate(String serviceClassName, Object provider, boolean owned) {
    }

    public ContractDiscovery() {
        this.warned = new HashSet<>();
    }

    public List<IntegrationServiceContract> adapt(Collection<Candidate> candidates) {
        List<IntegrationServiceContract> contracts = new ArrayList<>();
        for (Candidate candidate : candidates) {
            IntegrationServiceContract contract = adaptOne(candidate);
            if (contract != null) {
                contracts.add(contract);
            }
        }
        return contracts;
    }

    private IntegrationServiceContract adaptOne(Candidate candidate) {
        if (candidate == null || candidate.owned() || candidate.provider() == null) {
            return null;
        }
        if (candidate.provider() instanceof IntegrationServiceContract typed) {
            return typed;
        }
        if (!ReflectiveContractAdapter.supports(candidate.serviceClassName())) {
            return null;
        }

        try {
            return ReflectiveContractAdapter.create(candidate.provider(), candidate.serviceClassName());
        } catch (Throwable failure) {
            warnOnce(candidate, failure);
            return null;
        }
    }

    private void warnOnce(Candidate candidate, Throwable failure) {
        String signature = candidate.serviceClassName() + "/" + candidate.provider().getClass().getName();
        if (!warned.add(signature)) {
            return;
        }

        Gloss.logExceptionStack(false, failure, "Integration bridge: %s is not adaptable.", signature);
    }
}
