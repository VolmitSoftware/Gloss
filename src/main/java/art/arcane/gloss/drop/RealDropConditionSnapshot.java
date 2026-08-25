package art.arcane.gloss.drop;

import art.arcane.gloss.condition.GlossConditionContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

record RealDropConditionSnapshot(Location location, Map<String, Object> values) {

    RealDropConditionSnapshot {
        location = location.clone();
        values = Map.copyOf(values);
    }

    static RealDropConditionSnapshot capture(Item item, String eventType) {
        Location location = item.getLocation();
        World world = item.getWorld();
        ItemStack stack = item.getItemStack();
        UUID thrower = item.getThrower();
        Map<String, Object> values = new LinkedHashMap<>();
        putDropValues(values, item, stack, location, thrower);
        putSubjectValues(values, item, location);
        putSourceValues(values, thrower);
        putWorldValues(values, world);
        values.put("event.type", eventType);
        values.put("event.playerDrop", thrower != null);
        return new RealDropConditionSnapshot(location, values);
    }

    GlossConditionContext itemContext(Item item) {
        return new GlossConditionContext(null, item, null, location, values);
    }

    GlossConditionContext viewerContext(Player viewer) {
        return new GlossConditionContext(viewer, null, null, location, values);
    }

    private static void putDropValues(Map<String, Object> values, Item item, ItemStack stack,
                                      Location location, UUID thrower) {
        String id = item.getUniqueId().toString();
        values.put("drop.id", id);
        values.put("drop.uuid", id);
        values.put("drop.material", stack.getType().name());
        values.put("drop.amount", (double) stack.getAmount());
        values.put("drop.maxStackSize", (double) stack.getMaxStackSize());
        values.put("drop.world", item.getWorld().getName());
        values.put("drop.x", location.getX());
        values.put("drop.y", location.getY());
        values.put("drop.z", location.getZ());
        values.put("drop.blockX", (double) location.getBlockX());
        values.put("drop.blockY", (double) location.getBlockY());
        values.put("drop.blockZ", (double) location.getBlockZ());
        values.put("drop.onGround", item.isOnGround());
        values.put("drop.inWater", item.isInWater());
        values.put("drop.inLava", location.getBlock().getType() == Material.LAVA);
        values.put("drop.playerDropped", thrower != null);
        values.put("drop.customNamed", item.getCustomName() != null);
        values.put("drop.ticksLived", (double) item.getTicksLived());
        values.put("drop.pickupDelay", (double) item.getPickupDelay());
    }

    private static void putSubjectValues(Map<String, Object> values, Item item, Location location) {
        values.put("subject.name", item.getName());
        values.put("subject.uuid", item.getUniqueId().toString());
        values.put("subject.type", item.getType().getKey().getKey());
        values.put("subject.world", item.getWorld().getName());
        values.put("subject.x", location.getX());
        values.put("subject.y", location.getY());
        values.put("subject.z", location.getZ());
        values.put("subject.blockX", (double) location.getBlockX());
        values.put("subject.blockY", (double) location.getBlockY());
        values.put("subject.blockZ", (double) location.getBlockZ());
        values.put("subject.dead", item.isDead());
        values.put("subject.onGround", item.isOnGround());
        values.put("subject.inWater", item.isInWater());
        values.put("subject.fireTicks", (double) item.getFireTicks());
        values.put("subject.freezeTicks", (double) item.getFreezeTicks());
        values.put("subject.ticksLived", (double) item.getTicksLived());
    }

    private static void putSourceValues(Map<String, Object> values, UUID thrower) {
        boolean present = thrower != null;
        values.put("source.present", present);
        values.put("source.type", present ? "player" : "unknown");
        values.put("source.id", present ? thrower.toString() : "");
        values.put("source.uuid", present ? thrower.toString() : "");
    }

    private static void putWorldValues(Map<String, Object> values, World world) {
        values.put("world.name", world.getName());
        values.put("world.uuid", world.getUID().toString());
        values.put("world.environment", world.getEnvironment().name().toLowerCase(Locale.ROOT));
        values.put("world.difficulty", world.getDifficulty().name().toLowerCase(Locale.ROOT));
        values.put("world.time", (double) world.getTime());
        values.put("world.fullTime", (double) world.getFullTime());
        values.put("world.storm", world.hasStorm());
        values.put("world.thundering", world.isThundering());
        values.put("world.pvp", world.getPVP());
        values.put("world.players", (double) world.getPlayers().size());
    }
}
