package art.arcane.gloss.prompt;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.config.action.DialogActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTFloat;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeDialogTest {
    private final RecordingRuntime runtime = new RecordingRuntime();
    private final NativeDialogService service = new NativeDialogService(runtime);
    private final Context context = new Context(player(UUID.randomUUID()));

    @BeforeEach
    void start() {
        PacketEventsStub.install();
        service.enable();
    }

    @AfterEach
    void stop() {
        service.disable();
        PacketEventsStub.uninstall();
    }

    @Test
    void formRejectsInvalidGameWidthsDuplicateInputsAndUnsupportedKinds() {
        assertThrows(IllegalArgumentException.class, () -> definition("{\"title\":\"Test\",\"kind\":\"custom_screen\"}"));
        assertThrows(IllegalArgumentException.class, () -> definition("{\"body\":[{\"width\":1025}]}"));
        assertThrows(IllegalArgumentException.class, () -> definition("{\"inputs\":[{\"key\":\"x\"},{\"key\":\"x\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> definition("{\"inputs\":[{\"key\":\"invalid.key\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> definition("{\"kind\":\"confirmation\"}"));
        assertThrows(IllegalArgumentException.class, () -> definition("{\"inputs\":[{\"key\":\"x\",\"type\":\"number_range\",\"start\":0,\"end\":10,\"initial\":11}]}"));
    }

    @Test
    void responsesValidateNativeTypesLengthsChoiceMembershipRangeAndStep() {
        DialogDefinition definition = definition("""
            {"inputs":[{"key":"name","maxLength":4},
              {"key":"accepted","type":"boolean"},
              {"key":"mode","type":"single_option","options":[{"id":"safe"}]},
              {"key":"level","type":"number_range","start":0,"end":10,"initial":0,"step":2}]}
            """);
        NBTCompound valid = new NBTCompound();
        valid.setTag("name", new NBTString("Test"));
        valid.setTag("accepted", new NBTByte(true));
        valid.setTag("mode", new NBTString("safe"));
        valid.setTag("level", new NBTFloat(4));
        assertEquals(Map.of("name", "Test", "accepted", true, "mode", "safe", "level", 4.0), definition.validate(valid));
        NBTCompound invalid = valid.copy();
        invalid.setTag("name", new NBTString("Longer"));
        assertThrows(IllegalArgumentException.class, () -> definition.validate(invalid));
        invalid.setTag("name", new NBTString("Test"));
        invalid.setTag("accepted", new NBTInt(1));
        assertThrows(IllegalArgumentException.class, () -> definition.validate(invalid));
        invalid.setTag("accepted", new NBTByte(true));
        invalid.setTag("mode", new NBTString("forged"));
        assertThrows(IllegalArgumentException.class, () -> definition.validate(invalid));
        invalid.setTag("mode", new NBTString("safe"));
        for (float value : new float[]{3, 12, Float.NaN}) {
            invalid.setTag("level", new NBTFloat(value));
            assertThrows(IllegalArgumentException.class, () -> definition.validate(invalid));
        }
        valid.setTag("extra", new NBTString("forged"));
        assertThrows(IllegalArgumentException.class, () -> definition.validate(valid));
    }

    @Test
    void validResponseRunsOnceOnOwnerAndCannotReplayOrCrossConnections() {
        assertTrue(service.open(context, savingForm()));
        ResourceLocation id = runtime.responseId();
        NBTCompound payload = value("accepted");
        assertFalse(service.answer(player(context.player().getUniqueId()), id, payload));
        assertFalse(service.answer(context.player(), new ResourceLocation("gloss:dialog/wrong/0"), payload));
        assertTrue(service.answer(context.player(), id, payload));
        assertNull(context.variables.get("answer"));
        assertFalse(service.answer(context.player(), id, payload));
        runtime.runImmediate();
        assertEquals("accepted", context.variables.get("answer"));
        assertFalse(service.answer(context.player(), id, payload));
    }

    @Test
    void replacementForeignDialogQuitAndReloadInvalidateOldResponses() {
        service.open(context, savingForm());
        ResourceLocation first = runtime.responseId();
        Dialog firstPacket = runtime.shown;
        service.open(context, savingForm());
        ResourceLocation second = runtime.responseId();
        service.displayed(context.player().getUniqueId(), firstPacket);
        service.displayed(context.player().getUniqueId(), runtime.shown);
        assertNotEquals(first, second);
        assertFalse(service.answer(context.player(), first, value("old")));
        service.displayed(context.player().getUniqueId(), null);
        assertFalse(service.answer(context.player(), second, value("foreign")));
        assertEquals(0, runtime.cleared);
        service.open(context, savingForm());
        ResourceLocation third = runtime.responseId();
        assertTrue(service.answer(context.player(), third, value("queued")));
        service.disable();
        service.enable();
        runtime.runImmediate();
        assertNull(context.variables.get("answer"));
        service.open(context, savingForm());
        ResourceLocation fourth = runtime.responseId();
        service.cancel(context.player());
        assertFalse(service.answer(context.player(), fourth, value("quit")));
    }

    @Test
    void ownPacketKeepsClaimAndExpiredFormsClearOnlyTheirOwnDialog() {
        DialogDefinition form = definition("""
            {"timeoutTicks":2,"onTimeout":[{"type":"setSession","var":"expired","value":"true"}]}
            """);
        service.open(context, form);
        service.displayed(context.player().getUniqueId(), runtime.shown);
        ResourceLocation first = runtime.responseId();
        runtime.time = 100;
        assertFalse(service.answer(context.player(), first, null));
        service.sweep();
        runtime.runImmediate();
        assertEquals(true, context.variables.get("expired"));
        assertEquals(1, runtime.cleared);
        service.open(context, form);
        service.displayed(context.player().getUniqueId(), null);
        service.sweep();
        runtime.runImmediate();
        assertEquals(1, runtime.cleared);
    }

    @Test
    void unavailableCapabilityAndRetiredOriginNeverExecuteResponseActions() {
        runtime.supported = false;
        assertFalse(service.open(context, savingForm()));
        runtime.supported = true;
        service.open(context, savingForm());
        assertTrue(service.answer(context.player(), runtime.responseId(), value("late")));
        context.current.set(false);
        runtime.runImmediate();
        assertNull(context.variables.get("answer"));
    }

    @Test
    void replacementCreatesNoRetainedTimeoutTasksAndExpirationQueuesOnce() {
        for (int index = 0; index < 500; index++) {
            assertTrue(service.open(context, savingForm()));
        }
        assertTrue(runtime.tasks.isEmpty());
        runtime.time = 60000;
        service.sweep();
        service.sweep();
        assertEquals(1, runtime.tasks.size());
        runtime.runImmediate();
        assertEquals(1, runtime.cleared);
        service.sweep();
        assertTrue(runtime.tasks.isEmpty());
    }

    @Test
    void queuedClearCannotEraseAForeignOrReplacementDialog() {
        runtime.deferClears = true;
        service.open(context, savingForm());
        runtime.time = 60000;
        service.sweep();
        runtime.runImmediate();
        assertEquals(1, runtime.clears.size());
        service.displayed(context.player().getUniqueId(), null);
        runtime.runClears();
        assertEquals(0, runtime.cleared);
        service.open(context, savingForm());
        service.clear(context.player());
        service.open(context, savingForm());
        runtime.runClears();
        assertEquals(0, runtime.cleared);
        assertTrue(service.answer(context.player(), runtime.responseId(), value("current")));
    }

    @Test
    void inputValuesRemainScopedAfterDelayAndRetiredContextStopsContinuation() {
        List<MenuAction<?>> actions = MenuAction.resolve(List.of(
            action("{\"type\":\"delay\",\"ticks\":2}"),
            action("{\"type\":\"setSession\",\"var\":\"answer\",\"value\":\"input.value\"}")), "test", "dialog");
        List<Runnable> tasks = new ArrayList<>();
        DialogActionContext response = new DialogActionContext(context, Map.of("value", "later"));
        ActionProgram.Continuation continuation = (delay, task) -> tasks.add(task);
        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(actions, 0, response, continuation));
        tasks.removeFirst().run();
        assertEquals("later", context.variables.get("answer"));
        context.variables.set("answer", null);
        ActionProgram.run(actions, 0, response, continuation);
        context.current.set(false);
        tasks.removeFirst().run();
        assertNull(context.variables.get("answer"));
    }

    private static DialogDefinition savingForm() {
        return definition("""
            {"title":"Answer","inputs":[{"key":"value"}],
             "buttons":[{"label":"Save","actions":[{"type":"setSession","var":"answer","value":"input.value"}]}]}
            """);
    }

    private static DialogDefinition definition(String source) {
        return new DialogDefinition(DocumentParsers.parseJson("dialog", source, DialogActionData.class));
    }

    private static MenuActionData action(String source) {
        return DocumentParsers.parseJson("action", source, MenuActionData.class);
    }

    private static NBTCompound value(String text) {
        NBTCompound payload = new NBTCompound();
        payload.setTag("value", new NBTString(text));
        return payload;
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "DialogTester";
                case "isOnline", "isValid" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    private static final class Context implements ActionContext {
        private final Player player;
        private final SessionVariables variables = SessionVariables.of(Map.of(), Map.of());
        private final AtomicBoolean current = new AtomicBoolean(true);

        private Context(Player player) {
            this.player = player;
        }

        @Override public Player player() { return player; }
        @Override public String menuId() { return "test"; }
        @Override public String componentId() { return "dialog"; }
        @Override public HoloClickTrigger trigger() { return HoloClickTrigger.ANY; }
        @Override public boolean current() { return current.get(); }
        @Override public NavigationResult navigate(NavigationRequest request) { return NavigationResult.DENIED; }
        @Override public SessionVariables sessionVariables() { return variables; }
        @Override public ExprScope conditionScope() {
            return new ExprScope() {
                @Override public Object variable(String name) { return null; }
                @Override public Object call(String name, List<Object> args) { return null; }
            };
        }
    }

    private static final class RecordingRuntime implements NativeDialogService.Runtime {
        private final List<NativeDialogService.Scheduled> tasks = new ArrayList<>();
        private final List<BooleanSupplier> clears = new ArrayList<>();
        private Dialog shown;
        private boolean supported = true;
        private int cleared;
        private long time;
        private boolean deferClears;

        @Override public boolean supported(Player viewer) { return supported; }
        @Override public void show(Player viewer, Dialog dialog) { shown = dialog; }
        @Override public void clear(Player viewer, BooleanSupplier owned) {
            if (deferClears) clears.add(owned);
            else if (owned.getAsBoolean()) cleared++;
        }
        @Override public boolean schedule(Player viewer, NativeDialogService.Scheduled task) { return tasks.add(task); }
        @Override public long nowMillis() { return time; }
        @Override public void startSweep(Runnable sweep) { }
        @Override public void stopSweep() { }

        private ResourceLocation responseId() {
            return ((DynamicCustomAction) ((NoticeDialog) shown).getAction().getAction()).getId();
        }

        private void runImmediate() { run(false); }
        private void runClears() {
            for (BooleanSupplier clear : List.copyOf(clears)) {
                clears.remove(clear);
                if (clear.getAsBoolean()) cleared++;
            }
        }
        private void run(boolean delayed) {
            for (NativeDialogService.Scheduled task : List.copyOf(tasks)) {
                if ((task.delayTicks() > 0) == delayed) {
                    tasks.remove(task);
                    task.task().run();
                }
            }
        }
    }
}
