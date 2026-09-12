package art.arcane.gloss.marker;

import art.arcane.gloss.api.MarkerProvider;
import art.arcane.gloss.api.MarkerProviders;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class MarkerProvidersTest {
    private static final MarkerSpec ONE = MarkerSpec.at("one", "world", 1, 2, 3);
    private static final MarkerSpec TWO = MarkerSpec.at("two", "world", 4, 5, 6);

    @AfterEach
    void clear() {
        MarkerProviders.clear();
    }

    @Test
    void collectsFromEveryRegisteredProvider() {
        MarkerProviders.register(plugin("A"), viewer -> List.of(ONE));
        MarkerProviders.register(plugin("B"), viewer -> List.of(TWO));

        Assertions.assertEquals(List.of(ONE, TWO), MarkerProviders.collect(null));
    }

    @Test
    void unregisterDropsEveryProviderOfThatPlugin() {
        Plugin owner = plugin("A");
        MarkerProviders.register(owner, viewer -> List.of(ONE));
        MarkerProviders.register(plugin("B"), viewer -> List.of(TWO));

        MarkerProviders.unregister(owner);

        Assertions.assertEquals(List.of(TWO), MarkerProviders.collect(null));
        Assertions.assertEquals(1, MarkerProviders.all().size());
    }

    @Test
    void isolatesAThrowingProviderAndKeepsTheRest() {
        AtomicInteger calls = new AtomicInteger();
        MarkerProviders.register(plugin("Bad"), viewer -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider exploded");
        });
        MarkerProviders.register(plugin("Good"), viewer -> List.of(TWO));

        Assertions.assertEquals(List.of(TWO), MarkerProviders.collect(null));
        Assertions.assertEquals(List.of(TWO), MarkerProviders.collect(null));
        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void aProviderReturningNullContributesNothing() {
        MarkerProviders.register(plugin("A"), viewer -> null);

        Assertions.assertEquals(List.of(), MarkerProviders.collect(null));
    }

    @Test
    void handsTheViewerToEveryProvider() {
        Player viewer = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "Viewer";
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        MarkerProvider provider = seen -> List.of(MarkerSpec.at(seen.getName(), "world", 0, 0, 0));
        MarkerProviders.register(plugin("A"), provider);

        Assertions.assertEquals("Viewer", MarkerProviders.collect(viewer).getFirst().id());
    }

    private static Plugin plugin(String name) {
        return (Plugin) CharacterizationSupport.proxy(new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }
}
