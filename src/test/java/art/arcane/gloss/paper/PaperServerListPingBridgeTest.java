package art.arcane.gloss.paper;

import art.arcane.gloss.motd.PingDecorator;
import art.arcane.gloss.motd.MotdPolicy;
import com.destroystokyo.paper.network.StatusClient;
import art.arcane.gloss.service.PaperBridges;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.List;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.lang.reflect.Proxy;
import net.kyori.adventure.text.Component;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperServerListPingBridgeTest {
    @Test
    void requestUsesClientProtocolAndPoliciesHideSampleWithoutChangingServerProtocol() {
        StatusClient client = (StatusClient) Proxy.newProxyInstance(StatusClient.class.getClassLoader(),
            new Class<?>[]{StatusClient.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getProtocolVersion" -> 774;
                case "getVirtualHost" -> InetSocketAddress.createUnresolved("EVENT.EXAMPLE.ORG.", 25565);
                case "getAddress" -> new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
                case "isLegacy" -> false;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        PaperServerListPingEvent event = new PaperServerListPingEvent(client, Component.text("Original"),
            10, 20, "Before", 999, null);
        List<PaperServerListPingEvent.ListedPlayerInfo> sample = event.getListedPlayers();
        sample.add(new PaperServerListPingEvent.ListedPlayerInfo("Existing", UUID.randomUUID()));
        PaperServerListPingBridge bridge = new PaperServerListPingBridge();
        assertEquals(new MotdPolicy.Request("event.example.org", 774), bridge.request(event));
        bridge.decorate(event, new PingDecorator.RenderedPing(List.of("Unused"), 99, null, "Label", "hide",
            new MotdPolicy.Counts("offset", -2, null, null, true)));
        assertTrue(sample.isEmpty());
        assertTrue(event.shouldHidePlayers());
        event.setHidePlayers(false);
        assertEquals(8, event.getNumPlayers());
        assertEquals("Label", event.getVersion());
        assertEquals(999, event.getProtocolVersion());
    }

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
