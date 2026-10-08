package art.arcane.gloss.condition;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

public record EntityRelationshipSnapshot(UUID id, String teamEntry, boolean player, boolean invisible,
                                         boolean sneaking, boolean spectator, boolean npc) {
    public static EntityRelationshipSnapshot captureOnOwner(LivingEntity target) {
        if (!FoliaScheduler.isOwnedByCurrentRegion(target)) {
            throw new IllegalStateException("Relationship capture requires the entity's owning region");
        }
        Player player = target instanceof Player value ? value : null;
        UUID id = target.getUniqueId();
        return new EntityRelationshipSnapshot(id, player == null ? id.toString() : player.getName(),
            player != null, target.isInvisible(), player != null && player.isSneaking(),
            player != null && player.getGameMode() == GameMode.SPECTATOR, target.hasMetadata("NPC"));
    }
}
