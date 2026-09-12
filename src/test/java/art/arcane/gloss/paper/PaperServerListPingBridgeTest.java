package art.arcane.gloss.paper;

import art.arcane.gloss.motd.PingDecorator;
import art.arcane.gloss.service.PaperBridges;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperServerListPingBridgeTest {
    @Test
    void theBridgeLoadsThroughPaperBridgesAsAPingDecorator() {
        Optional<PingDecorator> decorator = PaperBridges.load(
            "com.destroystokyo.paper.event.server.PaperServerListPingEvent",
            "art.arcane.gloss.paper.PaperServerListPingBridge", PingDecorator.class);

        assertTrue(decorator.isPresent());
    }

    @Test
    void paperStillDeclaresTheListedPlayerInfoConstructorTheBridgeUses() throws ReflectiveOperationException {
        Class<?> listed = Class.forName(
            "com.destroystokyo.paper.event.server.PaperServerListPingEvent$ListedPlayerInfo");

        Constructor<?> constructor = listed.getConstructor(String.class, UUID.class);

        assertNotNull(constructor);
        assertEquals(2, constructor.getParameterCount());
    }

    @Test
    void paperStillDeclaresTheStringTypedPingSettersTheBridgeUses() throws ReflectiveOperationException {
        Method version = PaperServerListPingEvent.class.getMethod("setVersion", String.class);
        Method numPlayers = PaperServerListPingEvent.class.getMethod("setNumPlayers", int.class);
        Method listedPlayers = PaperServerListPingEvent.class.getMethod("getListedPlayers");

        assertEquals(void.class, version.getReturnType());
        assertEquals(void.class, numPlayers.getReturnType());
        assertEquals(java.util.List.class, listedPlayers.getReturnType());
    }
}
