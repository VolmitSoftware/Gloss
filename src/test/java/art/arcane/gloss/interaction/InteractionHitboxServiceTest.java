package art.arcane.gloss.interaction;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.MessageActionData;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionHitboxServiceTest {
    private record Sent(Player viewer, PacketWrapper<?> packet) {
    }

    private static final class RecordingSink implements InteractionHitboxService.Sink {
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(Player viewer, List<PacketWrapper<?>> packets) {
            for (PacketWrapper<?> packet : packets) {
                sent.add(new Sent(viewer, packet));
            }
        }

        private int spawnsFor(Player viewer, int entityId) {
            int count = 0;
            for (Sent frame : sent) {
                if (frame.viewer() == viewer && frame.packet() instanceof WrapperPlayServerSpawnEntity spawn
                    && spawn.getEntityId() == entityId) {
                    count++;
                }
            }
            return count;
        }

        private boolean destroyedFor(Player viewer, int entityId) {
            for (Sent frame : sent) {
                if (frame.viewer() == viewer && frame.packet() instanceof WrapperPlayServerDestroyEntities destroy) {
                    for (int id : destroy.getEntityIds()) {
                        if (id == entityId) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private WrapperPlayServerEntityMetadata metadataFor(Player viewer, int entityId) {
            for (Sent frame : sent) {
                if (frame.viewer() == viewer && frame.packet() instanceof WrapperPlayServerEntityMetadata metadata
                    && metadata.getEntityId() == entityId
                    && metadata.getEntityMetadata().stream().anyMatch(data -> data.getIndex() == 8)) {
                    return metadata;
                }
            }
            return null;
        }
    }

    private static final class RecordingAction extends MenuAction<MenuActionData> {
        private final List<HoloClickTrigger> triggers = new ArrayList<>();

        private RecordingAction() {
            super(new MessageActionData("hi", HoloClickTrigger.ANY, null, null));
        }

        @Override
        public ActionOutcome execute(ActionContext context) {
            triggers.add(context.trigger());
            return ActionOutcome.CONTINUE;
        }
    }

    private static final class TestContext implements ActionContext {
        private final Player player;
        private final HoloClickTrigger trigger;

        private TestContext(Player player, HoloClickTrigger trigger) {
            this.player = player;
            this.trigger = trigger;
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public String menuId() {
            return "test";
        }

        @Override
        public String componentId() {
            return "target";
        }

        @Override
        public HoloClickTrigger trigger() {
            return trigger;
        }

        @Override
        public NavigationResult navigate(NavigationRequest request) {
            return NavigationResult.NOT_FOUND;
        }
    }

    @BeforeAll
    static void installPacketEventsApi() {
        PacketEvents.setAPI(new StubPacketEventsApi());
    }

    @AfterAll
    static void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    private static World world() {
        UUID id = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> id;
                case "getName" -> "world";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "World";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Player player(Location eye) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getEyeLocation" -> eye.clone();
                case "getLocation" -> eye.clone().subtract(0.0D, 1.6D, 0.0D);
                case "isOnline" -> true;
                case "getName" -> "tester";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static InteractionTarget target(String id, Location position, RecordingAction action,
                                            AtomicReference<HoloClickTrigger> lastTrigger) {
        return new InteractionTarget("test", id, () -> position, 1.0F, 1.0F, List.of(action),
            (player, trigger) -> {
                lastTrigger.set(trigger);
                return new TestContext(player, trigger);
            }, () -> true);
    }

    @Test
    void aViewerNeverHoldsMoreHitboxesThanTheCapAndTheNearestWin() {
        World world = world();
        RecordingSink sink = new RecordingSink();
        List<Player> nearby = new ArrayList<>();
        InteractionHitboxService service = new InteractionHitboxService(sink,
            (anchor, range) -> List.copyOf(nearby), (player, action) -> action.run(),
            (player, distance) -> false, id -> false);
        Player viewer = player(new Location(world, 0.0D, 1.6D, 0.0D));
        nearby.add(viewer);
        int overflow = 8;
        List<InteractionHitboxService.Handle> handles = new ArrayList<>();
        for (int index = 0; index < InteractionHitboxService.MAX_HITBOXES_PER_VIEWER + overflow; index++) {
            handles.add(service.register(target("h" + index,
                new Location(world, 0.0D, 0.0D, index * 0.01D), new RecordingAction(),
                new AtomicReference<>())));
        }

        service.walk();

        assertEquals(InteractionHitboxService.MAX_HITBOXES_PER_VIEWER, service.spawnedCount());
        assertEquals(1, sink.spawnsFor(viewer, handles.getFirst().entityId()),
            "the nearest registration must be the one that keeps its slot");
        assertEquals(0, sink.spawnsFor(viewer, handles.getLast().entityId()));
    }

    @Test
    void aQuittingViewerIsForgottenRatherThanHeldUntilTheNextWalk() {
        World world = world();
        RecordingSink sink = new RecordingSink();
        List<Player> nearby = new ArrayList<>();
        InteractionHitboxService service = new InteractionHitboxService(sink,
            (anchor, range) -> List.copyOf(nearby), (player, action) -> action.run(),
            (player, distance) -> false, id -> false);
        Player viewer = player(new Location(world, 0.0D, 1.6D, 0.0D));
        nearby.add(viewer);
        service.register(target("a", new Location(world, 0.0D, 0.0D, 0.0D), new RecordingAction(),
            new AtomicReference<>()));
        service.walk();
        assertEquals(1, service.spawnedCount());

        service.forgetViewer(viewer.getUniqueId());

        assertEquals(0, service.spawnedCount());
    }

    @Test
    void hitboxesSpawnOnlyForViewersInRangeAndDespawnWhenTheyLeave() {
        World world = world();
        RecordingSink sink = new RecordingSink();
        List<Player> nearby = new ArrayList<>();
        InteractionHitboxService service = new InteractionHitboxService(sink, (anchor, range) -> List.copyOf(nearby),
            (player, action) -> action.run(), (player, distance) -> false, id -> false);
        Player near = player(new Location(world, 0.0D, 0.6D, -3.0D));
        Player far = player(new Location(world, 40.0D, 0.6D, -3.0D));
        nearby.add(near);
        InteractionHitboxService.Handle handle = service.register(target("a", new Location(world, 0.0D, 0.0D, 0.0D),
            new RecordingAction(), new AtomicReference<>()));

        service.walk();
        assertEquals(1, sink.spawnsFor(near, handle.entityId()));
        assertEquals(0, sink.spawnsFor(far, handle.entityId()));
        WrapperPlayServerEntityMetadata size = sink.metadataFor(near, handle.entityId());
        assertTrue(size != null && size.getEntityMetadata().stream().anyMatch(data -> data.getIndex() == 8 && data.getValue().equals(1.0F)));
        assertTrue(size.getEntityMetadata().stream().anyMatch(data -> data.getIndex() == 9 && data.getValue().equals(1.0F)));

        service.walk();
        assertEquals(1, sink.spawnsFor(near, handle.entityId()), "a spawned viewer is not respawned every walk");

        nearby.clear();
        service.walk();
        assertTrue(sink.destroyedFor(near, handle.entityId()));
        assertEquals(0, service.spawnedCount());
    }

    @Test
    void interactPacketsRouteToTheRightTargetWithTheDerivedTrigger() {
        World world = world();
        RecordingSink sink = new RecordingSink();
        List<Player> nearby = new ArrayList<>();
        InteractionHitboxService service = new InteractionHitboxService(sink, (anchor, range) -> List.copyOf(nearby),
            (player, action) -> action.run(), (player, distance) -> false, id -> false);
        Player viewer = player(new Location(world, 0.0D, 0.6D, -3.0D, 0.0F, 0.0F));
        nearby.add(viewer);
        RecordingAction first = new RecordingAction();
        RecordingAction second = new RecordingAction();
        AtomicReference<HoloClickTrigger> lastTrigger = new AtomicReference<>();
        InteractionHitboxService.Handle firstHandle = service.register(target("a", new Location(world, 5.0D, 0.0D, 0.0D), first, lastTrigger));
        InteractionHitboxService.Handle secondHandle = service.register(target("b", new Location(world, 0.0D, 0.0D, 0.0D), second, lastTrigger));
        service.walk();

        assertTrue(service.handleInteract(viewer, secondHandle.entityId(),
            WrapperPlayClientInteractEntity.InteractAction.INTERACT, true, Optional.empty()));
        assertEquals(List.of(HoloClickTrigger.SHIFT_RIGHT_CLICK), second.triggers);
        assertTrue(first.triggers.isEmpty());
        assertEquals(HoloClickTrigger.SHIFT_RIGHT_CLICK, lastTrigger.get());

        assertTrue(service.handleInteract(viewer, secondHandle.entityId(),
            WrapperPlayClientInteractEntity.InteractAction.ATTACK, false, Optional.empty()));
        assertEquals(List.of(HoloClickTrigger.SHIFT_RIGHT_CLICK, HoloClickTrigger.LEFT_CLICK), second.triggers);

        assertFalse(service.handleInteract(viewer, 999_999, WrapperPlayClientInteractEntity.InteractAction.ATTACK,
            false, Optional.empty()), "unknown entity ids are not ours");
        assertTrue(first.triggers.isEmpty(), "a target the viewer is not looking at is never dispatched");
        assertTrue(service.handleInteract(viewer, firstHandle.entityId(),
            WrapperPlayClientInteractEntity.InteractAction.ATTACK, false, Optional.empty()));
        assertTrue(first.triggers.isEmpty(), "the eye ray misses the first target, so the click is dropped");
    }

    @Test
    void obstructedClicksAreDroppedAndUnregisteredTargetsDespawn() {
        World world = world();
        RecordingSink sink = new RecordingSink();
        List<Player> nearby = new ArrayList<>();
        AtomicBoolean obstructed = new AtomicBoolean(true);
        InteractionHitboxService service = new InteractionHitboxService(sink, (anchor, range) -> List.copyOf(nearby),
            (player, action) -> action.run(), (player, distance) -> obstructed.get(), id -> false);
        Player viewer = player(new Location(world, 0.0D, 0.6D, -3.0D, 0.0F, 0.0F));
        nearby.add(viewer);
        RecordingAction action = new RecordingAction();
        InteractionHitboxService.Handle handle = service.register(target("a", new Location(world, 0.0D, 0.0D, 0.0D), action, new AtomicReference<>()));
        service.walk();

        assertTrue(service.handleInteract(viewer, handle.entityId(),
            WrapperPlayClientInteractEntity.InteractAction.INTERACT, false, Optional.empty()));
        assertTrue(action.triggers.isEmpty(), "a block between the eye and the hitbox drops the click");
        obstructed.set(false);
        assertTrue(service.handleInteract(viewer, handle.entityId(),
            WrapperPlayClientInteractEntity.InteractAction.INTERACT, false, Optional.empty()));
        assertEquals(List.of(HoloClickTrigger.RIGHT_CLICK), action.triggers);

        service.unregister(handle);
        assertTrue(sink.destroyedFor(viewer, handle.entityId()));
        assertFalse(service.handleInteract(viewer, handle.entityId(),
            WrapperPlayClientInteractEntity.InteractAction.INTERACT, false, Optional.empty()));
        assertNull(service.target(handle.entityId()));
    }

    @Test
    void bedrockViewersNeverReceiveHitboxes() {
        World world = world();
        RecordingSink sink = new RecordingSink();
        Player viewer = player(new Location(world, 0.0D, 0.6D, -3.0D));
        InteractionHitboxService service = new InteractionHitboxService(sink, (anchor, range) -> List.of(viewer),
            (player, action) -> action.run(), (player, distance) -> false, id -> true);
        InteractionHitboxService.Handle handle = service.register(target("a", new Location(world, 0.0D, 0.0D, 0.0D),
            new RecordingAction(), new AtomicReference<>()));
        service.walk();
        assertEquals(0, sink.spawnsFor(viewer, handle.entityId()));
    }

    private static final class StubPacketEventsApi extends PacketEventsAPI<Object> {
        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public void init() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public Object getPlugin() {
            return this;
        }

        @Override
        public ServerManager getServerManager() {
            return () -> ServerVersion.V_26_1_2;
        }

        @Override
        public ProtocolManager getProtocolManager() {
            return null;
        }

        @Override
        public PlayerManager getPlayerManager() {
            return null;
        }

        @Override
        public NettyManager getNettyManager() {
            return null;
        }

        @Override
        public ChannelInjector getInjector() {
            return null;
        }
    }
}
