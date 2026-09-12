package art.arcane.gloss.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns TAB's header and footer into the {@code tablist.json} header-footer block, with one variant
 * per permission group gated on {@code inGroup('viewer', '<group>')}.
 */
public final class TabHeaderFooterConverter {
    private static final int SCHEMA_VERSION = 2;
    private static final long INITIAL_REVISION = 1L;
    private static final int FIRST_PRIORITY = 100;

    public List<DocumentImportEntry> convert(TabHeaderFooterScanner.Draft draft) {
        JsonObject presentation = new JsonObject();
        presentation.addProperty("header", draft.header());
        presentation.addProperty("footer", draft.footer());
        JsonArray variants = new JsonArray();
        int priority = FIRST_PRIORITY;
        for (Map.Entry<String, String[]> group : draft.groups().entrySet()) {
            JsonObject variantPresentation = new JsonObject();
            variantPresentation.addProperty("header", group.getValue()[0]);
            variantPresentation.addProperty("footer", group.getValue()[1]);
            JsonObject variant = new JsonObject();
            variant.addProperty("id", LegacyBoardConverter.slug(group.getKey()));
            variant.addProperty("priority", priority);
            variant.addProperty("when", "inGroup('viewer', '" + group.getKey() + "')");
            variant.add("presentation", variantPresentation);
            variants.add(variant);
            priority += 10;
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
        List<String> warnings = new ArrayList<>(draft.warnings());
        warnings.add("listNames were not imported; the Gloss tablist keeps its own list-name rules");
        return List.of(new DocumentImportEntry("tablist", "tablist",
                LegacyBoardConverter.pretty(document), LegacyImportDisposition.READY, "", warnings));
    }
}
