package art.arcane.gloss.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The merged schema of every enabled behavior document. A key is shared across documents
 * (Decision D13): each declaration of it must agree or the merge refuses with both documents named.
 */
public final class StateDeclarations {
    private static final StateDeclarations EMPTY = new StateDeclarations(Map.of(), Map.of());

    private final Map<String, StateSchema> schemas;
    private final Map<String, String> owners;

    private StateDeclarations(Map<String, StateSchema> schemas, Map<String, String> owners) {
        this.schemas = Map.copyOf(schemas);
        this.owners = Map.copyOf(owners);
    }

    public static StateDeclarations empty() {
        return EMPTY;
    }

    public static StateDeclarations merge(Map<String, List<StateSchema>> byDocument) {
        Objects.requireNonNull(byDocument, "byDocument");
        List<String> documents = new ArrayList<>(byDocument.keySet());
        documents.sort(String::compareTo);
        Map<String, StateSchema> schemas = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        for (String document : documents) {
            for (StateSchema schema : byDocument.get(document)) {
                StateSchema existing = schemas.get(schema.key());
                if (existing == null) {
                    schemas.put(schema.key(), schema);
                    owners.put(schema.key(), document);
                    continue;
                }
                if (!existing.agreesWith(schema)) {
                    throw new StateConflictException(schema.key(), owners.get(schema.key()), document);
                }
            }
        }
        return new StateDeclarations(schemas, owners);
    }

    public StateSchema get(String key) {
        return key == null ? null : schemas.get(key);
    }

    public String owner(String key) {
        return owners.get(key);
    }

    public Set<String> keys() {
        return schemas.keySet();
    }

    public boolean isEmpty() {
        return schemas.isEmpty();
    }
}
