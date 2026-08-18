package art.arcane.gloss.api;

import java.util.UUID;

public interface HologramViewers {
    void blacklist();

    void whitelist();

    void add(UUID playerId);

    void remove(UUID playerId);

    void clear();
}
