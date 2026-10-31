package art.arcane.gloss.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class CmiTabListConverter {
    private static final int SCHEMA_VERSION = 3;
    private static final int ANIMATION_SCHEMA_VERSION = 1;
    private static final long INITIAL_REVISION = 1L;
    private static final long TICK_MILLIS = 50L;
    private static final int FIRST_PRIORITY = 100;

    public List<DocumentImportEntry> convert(CmiTabListScanner.Draft draft) {
        List<DocumentImportEntry> entries = new ArrayList<>();
        JsonObject presentation = presentation("cmi-tablist", draft.general(), draft.intervalTicks(), entries);
        JsonArray variants = new JsonArray();
        List<Map.Entry<String, CmiTabListScanner.Format>> groups = new ArrayList<>(draft.groups().entrySet());
        groups.sort(Comparator.comparingInt(group -> order(group.getKey())));
        for (Map.Entry<String, CmiTabListScanner.Format> group : groups) {
            String id = "cmi-tablist-" + LegacyBoardConverter.slug(group.getKey());
            JsonObject variant = new JsonObject();
            variant.addProperty("id", id);
            variant.addProperty("priority", FIRST_PRIORITY + Math.max(0, order(group.getKey())));
            variant.addProperty("when", "hasPermission('viewer', 'cmi.tablist." + group.getKey() + "')");
            variant.add("presentation", presentation(id, group.getValue(), draft.intervalTicks(), entries));
            variants.add(variant);
        }
        JsonObject headerFooter = new JsonObject();
        headerFooter.addProperty("enabled", draft.enabled());
        headerFooter.addProperty("show", true);
        headerFooter.add("presentation", presentation);
        headerFooter.add("variants", variants);
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", SCHEMA_VERSION);
        document.addProperty("revision", INITIAL_REVISION);
        document.addProperty("show", true);
        document.add("headerFooter", headerFooter);
        entries.add(new DocumentImportEntry("tablist", "tablist", LegacyBoardConverter.pretty(document),
                LegacyImportDisposition.READY, "", draft.warnings()));
        return List.copyOf(entries);
    }

    private static JsonObject presentation(String id, CmiTabListScanner.Format format, int intervalTicks,
                                           List<DocumentImportEntry> entries) {
        JsonObject presentation = new JsonObject();
        presentation.addProperty("header", text(id + "-header", format.header(), intervalTicks, entries));
        presentation.addProperty("footer", text(id + "-footer", format.footer(), intervalTicks, entries));
        return presentation;
    }

    private static String text(String id, List<String> frames, int intervalTicks,
                               List<DocumentImportEntry> entries) {
        if (frames.size() < 2 || intervalTicks < 0) {
            return frames.getFirst();
        }
        JsonArray values = new JsonArray();
        frames.forEach(values::add);
        JsonObject animation = new JsonObject();
        animation.addProperty("schemaVersion", ANIMATION_SCHEMA_VERSION);
        animation.addProperty("revision", INITIAL_REVISION);
        animation.addProperty("show", true);
        animation.addProperty("mode", "ascend");
        animation.addProperty("frameIntervalMs", intervalTicks * TICK_MILLIS);
        animation.add("frames", values);
        entries.add(new DocumentImportEntry("animations", id, LegacyBoardConverter.pretty(animation),
                LegacyImportDisposition.READY, "", List.of()));
        return "|animation." + id + "|";
    }

    private static int order(String key) {
        try {
            return Integer.parseInt(key.strip());
        } catch (NumberFormatException failure) {
            return 0;
        }
    }
}
