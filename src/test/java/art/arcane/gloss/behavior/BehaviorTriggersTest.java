package art.arcane.gloss.behavior;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorTriggersTest {
    private final World world = BehaviorTestSupport.world("survival");
    private final Player alice = BehaviorTestSupport.player("alice", Set.of(), world, 1.0D);
    private final Player bob = BehaviorTestSupport.player("bob", Set.of(), world, 2.0D);
    private final Zombie zombie = BehaviorTestSupport.zombie(world);

    @Test
    void playerEventsCarryTheViewerAsSubjectToo() {
        TriggerEvent join = TriggerEvent.viewer(alice);
        assertSame(alice, join.viewer());
        assertSame(alice, join.subject());
        assertNull(join.source());
        assertTrue(join.args().isEmpty());
        assertEquals(world, join.location().getWorld());
    }

    @Test
    void deathAndKillSwapTheRoles() {
        TriggerEvent death = TriggerEvent.death(alice, zombie, "entity_attack");
        assertSame(alice, death.viewer());
        assertSame(alice, death.subject());
        assertSame(zombie, death.source());
        assertEquals("entity_attack", death.args().get("cause"));

        TriggerEvent kill = TriggerEvent.kill(bob, zombie);
        assertSame(bob, kill.viewer());
        assertSame(zombie, kill.subject());
        assertSame(bob, kill.source());
        assertEquals("zombie", kill.selector());
    }

    @Test
    void damageViewerIsTheVictimWhenAPlayerElseTheDamager() {
        TriggerEvent hurt = TriggerEvent.damage(alice, zombie, 3.5D, "entity_attack");
        assertSame(alice, hurt.viewer());
        assertSame(alice, hurt.subject());
        assertSame(zombie, hurt.source());
        assertEquals(3.5D, hurt.args().get("amount"));
        assertEquals("entity_attack", hurt.args().get("cause"));

        TriggerEvent hit = TriggerEvent.damage(zombie, bob, 1.0D, "entity_attack");
        assertSame(bob, hit.viewer());
        assertSame(zombie, hit.subject());
        assertSame(bob, hit.source());

        assertNull(TriggerEvent.damage(zombie, zombie, 1.0D, "x").viewer());
    }

    @Test
    void chatBlocksItemsWorldsMenusAndEmitsExposeTheirArgsAndSelectors() {
        TriggerEvent chat = TriggerEvent.chat(alice, "!help me");
        assertEquals("!help me", chat.args().get("message"));
        assertEquals("!help me", chat.selector());

        TriggerEvent block = TriggerEvent.block(alice, "minecraft:diamond_ore", alice.getLocation());
        assertEquals("minecraft:diamond_ore", block.args().get("block"));
        assertEquals("minecraft:diamond_ore", block.selector());

        TriggerEvent pickup = TriggerEvent.item(alice, "minecraft:apple", 3);
        assertEquals("minecraft:apple", pickup.args().get("material"));
        assertEquals(3.0D, pickup.args().get("amount"));
        assertEquals("minecraft:apple", pickup.selector());

        TriggerEvent worlds = TriggerEvent.worldChange(alice, "lobby", "survival");
        assertEquals("lobby", worlds.args().get("from"));
        assertEquals("survival", worlds.args().get("to"));

        TriggerEvent click = TriggerEvent.menu(alice, "shop", "buy");
        assertEquals("shop", click.selector());
        assertEquals("buy", click.secondary());
        assertEquals("shop", click.args().get("menu"));
        assertEquals("buy", click.args().get("component"));

        TriggerEvent emit = TriggerEvent.emit(null, "quest.complete", Map.of("quest", "mill"));
        assertNull(emit.viewer());
        assertEquals("quest.complete", emit.selector());
        assertEquals("mill", emit.args().get("quest"));

        TriggerEvent command = TriggerEvent.command(bob, "daily", Map.of("bonus", "2"));
        assertEquals("daily", command.selector());
        assertEquals("2", command.args().get("bonus"));
        assertSame(bob, command.viewer());
    }

    @Test
    void commandArgumentsParseKeyValuePairsAndBareWords() {
        Map<String, Object> parsed = TriggerEvent.parseArguments("quest=mill amount=3 verbose flag=true");

        assertEquals("mill", parsed.get("quest"));
        assertEquals(3.0D, parsed.get("amount"));
        assertEquals(true, parsed.get("verbose"));
        assertEquals(true, parsed.get("flag"));
        assertTrue(TriggerEvent.parseArguments("").isEmpty());
        assertTrue(TriggerEvent.parseArguments(null).isEmpty());
    }
}
