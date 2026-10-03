package art.arcane.gloss.prompt;

import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What a sign editor gives back. The client returns four lines whatever the player typed, so the
 * join is what decides whether a blank second line becomes a double space in the answer.
 */
class SignPromptTest {
    @Test
    void fakeBlockPacketsContainNativeSignStatesOnBothSupportedProtocols() {
        PacketEventsStub.install();
        try {
            for (ServerVersion version : new ServerVersion[]{ServerVersion.V_26_2, ServerVersion.V_26_3}) {
                WrappedBlockState state = SignPrompt.signState(version.toClientVersion());
                WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(new Vector3i(1, 68, 2), state);
                assertEquals(StateTypes.OAK_SIGN, state.getType());
                assertEquals(StateTypes.OAK_SIGN,
                    WrappedBlockState.getByGlobalId(version.toClientVersion(), packet.getBlockId()).getType());
            }
        } finally {
            PacketEventsStub.uninstall();
        }
    }

    @Test
    void restorationUsesTheCapturedBlockAndSkipsADifferentWorld() {
        World originalWorld = world();
        AtomicReference<World> currentWorld = new AtomicReference<>(originalWorld);
        AtomicReference<BlockData> restored = new AtomicReference<>();
        BlockData original = (BlockData) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{BlockData.class}, (proxy, method, args) -> null);
        Player viewer = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isOnline" -> true;
                case "getWorld" -> currentWorld.get();
                case "sendBlockChange" -> {
                    restored.set((BlockData) args[1]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        SignPrompt.Editor editor = new SignPrompt.Editor(viewer, new Vector3i(1, 68, 2),
            new Location(originalWorld, 1, 68, 2), original,
            new PromptRequest(PromptRequest.SIGN, "", "", "", List.of(), 20, null));

        SignPrompt.restoreSnapshot(editor);
        assertSame(original, restored.get());
        restored.set(null);
        currentWorld.set(world());
        SignPrompt.restoreSnapshot(editor);
        assertNull(restored.get());
    }

    private World world() {
        return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void initialTextSeedsFourClientLines() {
        assertArrayEquals(new String[]{"hello", "", "", ""}, SignPrompt.initialLines("hello"));
        assertArrayEquals(new String[]{"one", "two", "three", "four"},
            SignPrompt.initialLines("one\ntwo\nthree\nfour"));
        assertArrayEquals(new String[]{"", "", "", ""}, SignPrompt.initialLines(""));
        assertArrayEquals(new String[]{"one", "two", "three", "four"},
            SignPrompt.initialLines("one\ntwo\nthree\nfour\nfive"));
    }

    @Test
    void theFourLinesJoinIntoOneAnswer() {
        assertEquals("hello there", SignPrompt.join(new String[]{"hello", "there", "", ""}));
        assertEquals("a b c d", SignPrompt.join(new String[]{"a", "b", "c", "d"}));
    }

    @Test
    void blankLinesInTheMiddleDoNotDoubleTheSpacing() {
        assertEquals("hello there", SignPrompt.join(new String[]{"hello", "", "there", ""}));
        assertEquals("hello", SignPrompt.join(new String[]{"", "hello", "  ", ""}));
    }

    @Test
    void anEmptySignIsAnEmptyAnswer() {
        assertEquals("", SignPrompt.join(new String[]{"", "", "", ""}));
        assertEquals("", SignPrompt.join(new String[0]));
        assertEquals("", SignPrompt.join(null));
    }

    @Test
    void theEditorSitsWellBelowThePlayerSoNoRealBlockIsTouched() {
        assertEquals(-3, SignPrompt.EDITOR_Y_OFFSET);
    }
}
