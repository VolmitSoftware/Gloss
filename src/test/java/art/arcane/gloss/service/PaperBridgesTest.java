package art.arcane.gloss.service;

import art.arcane.gloss.indicator.DamageIndicatorCriticality;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperBridgesTest {
    @Test
    void missingPaperApiClassYieldsEmptySilently() {
        Optional<Object> bridge = PaperBridges.load("io.papermc.paper.does.not.Exist",
            "art.arcane.gloss.paper.PaperDamageIndicatorCriticality", Object.class);
        assertTrue(bridge.isEmpty());
    }

    @Test
    void presentPaperApiLoadsTheBridgeThroughItsBukkitTypedContract() {
        Optional<DamageIndicatorCriticality> bridge = PaperBridges.load(
            "org.bukkit.event.entity.EntityDamageByEntityEvent",
            "art.arcane.gloss.paper.PaperDamageIndicatorCriticality", DamageIndicatorCriticality.class);
        assertTrue(bridge.isPresent());
    }

    @Test
    void brokenBridgeYieldsEmpty() {
        Optional<Object> bridge = PaperBridges.load("org.bukkit.entity.Player",
            "art.arcane.gloss.paper.NoSuchBridge", Object.class);
        assertTrue(bridge.isEmpty());
    }
}
