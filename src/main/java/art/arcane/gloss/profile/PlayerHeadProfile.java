package art.arcane.gloss.profile;

import java.util.Objects;
import java.util.UUID;

/**
 * The parts of a Mojang account a skull needs, lifted out of the Bukkit profile types so the cache
 * that stores them can be exercised without a running server.
 *
 * @param uniqueId the account id the client resolves the skin against; never null, because an
 *                 account without an id is an unknown name, not a resolved one
 * @param name     the account's official capitalization, which is what the head is cached under and
 *                 what an operator sees in a log line
 * @param skinUrl  the {@code textures.minecraft.net} skin URL, or null for an account that never
 *                 uploaded a skin. Null is a resolved head, not a failure: the client falls back to
 *                 the default skin that account's id already selects
 */
public record PlayerHeadProfile(UUID uniqueId, String name, String skinUrl) {
  public PlayerHeadProfile {
    Objects.requireNonNull(uniqueId, "uniqueId");
    Objects.requireNonNull(name, "name");
  }
}
