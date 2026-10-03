package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.WaypointSpec;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.nameplate.NameplateDoc;
import art.arcane.gloss.nameplate.NameplateService;
import art.arcane.gloss.nametag.NametagDoc;
import art.arcane.gloss.nametag.NametagService;
import art.arcane.gloss.surface.SurfaceDoc;
import art.arcane.gloss.surface.SurfaceService;
import art.arcane.gloss.util.common.PacketTeamAllocator;
import art.arcane.gloss.waypoint.WaypointDoc;
import art.arcane.gloss.waypoint.WaypointService;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationHotloadTest {
    @TempDir
    File folder;
    private final AtomicLong clock = new AtomicLong();
    private Object previousServer;
    private Gloss previousGloss;
    private Gloss plugin;

    @BeforeEach
    void install() throws Exception {
        Server server = CharacterizationSupport.server(Map.of());
        previousServer = CharacterizationSupport.installServer(server);
        plugin = CharacterizationSupport.bareGloss(server);
        CharacterizationSupport.setField(plugin, "dataFolder", folder);
        CharacterizationSupport.setField(plugin, "teams", new PacketTeamAllocator());
        previousGloss = CharacterizationSupport.installGloss(plugin);
    }

    @AfterEach
    void restore() throws Exception {
        CharacterizationSupport.restoreGloss(previousGloss);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void queuedNametagUpdateAndDeletionReachPresentationInSameDelta() throws Exception {
        DocumentRegistry<NametagDoc> registry = registry(NametagDoc.KIND, NametagDoc::parse, NametagDoc::revision);
        NametagService service = new NametagService(plugin);
        CharacterizationSupport.setField(service, "registry", registry);
        write(1, "");
        registry.reload();
        invoke(service, "rebuildRuntimes", Map.class, registry.snapshot());
        assertEquals(1, service.runtimes().getFirst().doc().revision());

        write(2, "");
        dispatch(service, registry);
        assertEquals(2, service.runtimes().getFirst().doc().revision());
        Files.delete(new File(folder, "garden.json").toPath());
        dispatch(service, registry);
        assertTrue(service.runtimes().isEmpty());
        registry.close();
    }

    @Test
    void queuedSurfaceUpdateAndDeletionReachPresentationInSameDelta() throws Exception {
        DocumentRegistry<SurfaceDoc> registry = registry(SurfaceDoc.KIND, SurfaceDoc::parse, SurfaceDoc::revision);
        SurfaceService service = new SurfaceService(plugin);
        CharacterizationSupport.setField(service, "registry", registry);
        String presentation = ",\"surface\":\"actionbar\",\"presentation\":{\"text\":\"Garden\"}";
        write(1, presentation);
        registry.reload();
        invoke(service, "rebuildRuntimes", Map.class, registry.snapshot());
        assertEquals(1, service.runtimes().getFirst().doc().revision());

        write(2, presentation);
        dispatch(service, registry);
        assertEquals(2, service.runtimes().getFirst().doc().revision());
        Files.delete(new File(folder, "garden.json").toPath());
        dispatch(service, registry);
        assertTrue(service.runtimes().isEmpty());
        registry.close();
    }

    @Test
    void nameplatePollPublishesCurrentUpdateAndDeletion() throws Exception {
        DocumentRegistry<NameplateDoc> registry = registry(NameplateDoc.KIND, NameplateDoc::parse, NameplateDoc::revision);
        NameplateService service = new NameplateService(plugin);
        CharacterizationSupport.setField(service, "registry", registry);
        write(1, "");
        registry.reload();
        invoke(service, "rebuild", Map.class, registry.snapshot());
        assertEquals(1, service.documents().getFirst().doc().revision());

        write(2, "");
        poll(service);
        assertEquals(2, service.documents().getFirst().doc().revision());
        Files.delete(new File(folder, "garden.json").toPath());
        poll(service);
        assertTrue(service.documents().isEmpty());
        registry.close();
    }

    @Test
    void waypointPollPublishesCurrentRangeAndDeletion() throws Exception {
        DocumentRegistry<WaypointDoc> registry = registry(WaypointDoc.KIND, WaypointDoc::parse, WaypointDoc::revision);
        WaypointService service = new WaypointService(plugin);
        CharacterizationSupport.setField(service, "registry", registry);
        String anchor = ",\"anchor\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0},\"range\":";
        write(1, anchor + "32");
        registry.reload();
        invoke(service, "rebuild", Map.class, registry.snapshot());
        assertEquals(32, waypoint(service).range());

        write(2, anchor + "128");
        poll(service);
        assertEquals(128, waypoint(service).range());
        Files.delete(new File(folder, "garden.json").toPath());
        poll(service);
        assertEquals(List.of(), CharacterizationSupport.getField(service, "documents"));
        registry.close();
    }

    private <T> DocumentRegistry<T> registry(String kind, DocumentParser<T> parser,
                                            ToLongFunction<T> revision) {
        return DocumentRegistry.folder(kind, folder, parser, revision, file -> false, clock::get);
    }

    private void write(long revision, String properties) throws Exception {
        File file = new File(folder, "garden.json");
        Files.writeString(file.toPath(), "{\"schemaVersion\":1,\"revision\":" + revision + properties + "}");
        assertTrue(file.setLastModified(file.lastModified() + 5000));
    }

    private void poll(Object service) throws Exception {
        DocumentRegistry<?> registry = (DocumentRegistry<?>) CharacterizationSupport.getField(service, "registry");
        Map<String, ?> committed = registry.snapshot();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.snapshot().equals(committed) && System.nanoTime() < deadline) {
            CharacterizationSupport.invoke(service, "poll", new Class<?>[0]);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            Thread.sleep(25);
        }
        assertFalse(registry.snapshot().equals(committed));
    }

    private <T> void dispatch(Object service, DocumentRegistry<T> registry) throws Exception {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12));
        DocumentDelta delta = DocumentDelta.EMPTY;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (delta.isEmpty() && System.nanoTime() < deadline) {
            delta = registry.poll();
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            Thread.sleep(25);
        }
        assertFalse(delta.isEmpty());
        DocumentDelta pendingDelta = delta;
        Map<String, GlossDocument<T>> committed = registry.snapshot();
        Map<String, GlossDocument<T>> pending = registry.snapshot(delta);
        List<Runnable> queued = new ArrayList<>();
        assertTrue(registry.dispatch(delta, task -> { queued.add(task); return true; },
            () -> invoke(service, "applyDelta", DocumentDelta.class, pendingDelta)));
        assertEquals(committed, registry.snapshot());
        assertEquals(1, queued.size());
        queued.getFirst().run();
        assertEquals(pending, registry.snapshot());
    }

    private WaypointSpec waypoint(WaypointService service) throws Exception {
        List<?> entries = (List<?>) CharacterizationSupport.getField(service, "documents");
        return (WaypointSpec) CharacterizationSupport.invoke(entries.getFirst(), "spec", new Class<?>[0]);
    }

    private void invoke(Object service, String method, Class<?> parameter, Object value) {
        CharacterizationSupport.invoke(service, method, new Class<?>[]{parameter}, value);
    }
}
