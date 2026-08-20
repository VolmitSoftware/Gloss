package art.arcane.gloss.drop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

public final class DropNameFormatter {
    private static final String ENTRY_SEPARATOR = "&8, &7";

    private DropNameFormatter() {
    }

    public static String format(String template, int count, String typeName) {
        return template
            .replace("{count}", Integer.toString(count))
            .replace("{type}", typeName);
    }

    public static boolean preservesExistingName(boolean preserveCustomNames, boolean hasCustomName, boolean glossOwned) {
        return preserveCustomNames && hasCustomName && !glossOwned;
    }

    public static boolean ownsExistingName(boolean marked, String lastRendered, String currentName) {
        return marked && (lastRendered == null || Objects.equals(lastRendered, currentName));
    }

    public static String typeLabel(boolean useItemDisplayNames, String displayName, String materialName) {
        if (!useItemDisplayNames || displayName == null || displayName.isBlank()) {
            return materialName;
        }

        return displayName;
    }

    public static String formatBundle(String template, List<BundleContent> contents, int entryLimit, IntFunction<String> moreRenderer) {
        List<BundleContent> aggregated = aggregate(contents);
        if (aggregated.isEmpty()) {
            return "";
        }

        int limit = Math.max(1, entryLimit);
        int total = 0;
        for (BundleContent content : aggregated) {
            total += content.amount();
        }

        StringBuilder rendered = new StringBuilder();
        int shown = Math.min(limit, aggregated.size());
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                rendered.append(ENTRY_SEPARATOR);
            }
            BundleContent content = aggregated.get(index);
            rendered.append(content.amount()).append("x ").append(content.type());
        }
        int remaining = aggregated.size() - shown;
        if (remaining > 0) {
            rendered.append(ENTRY_SEPARATOR).append(moreRenderer.apply(remaining));
        }

        return template
            .replace("{total}", Integer.toString(total))
            .replace("{contents}", rendered.toString());
    }

    public static List<String> formatBundleLines(String headerTemplate, String entryTemplate,
                                                  String moreTemplate, List<BundleContent> contents,
                                                  int entryLimit) {
        List<BundleContent> aggregated = aggregate(contents);
        if (aggregated.isEmpty()) {
            return List.of();
        }

        int total = 0;
        for (BundleContent content : aggregated) {
            total += content.amount();
        }

        int shown = Math.min(Math.max(1, entryLimit), aggregated.size());
        List<String> lines = new ArrayList<>(shown + 2);
        lines.add(headerTemplate.replace("{total}", Integer.toString(total)));
        for (int index = 0; index < shown; index++) {
            BundleContent content = aggregated.get(index);
            lines.add(entryTemplate
                .replace("{count}", Integer.toString(content.amount()))
                .replace("{type}", content.type()));
        }
        int remaining = aggregated.size() - shown;
        if (remaining > 0) {
            lines.add(moreTemplate.replace("{remaining}", Integer.toString(remaining)));
        }
        return List.copyOf(lines);
    }

    public static List<BundleContent> aggregate(List<BundleContent> contents) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (BundleContent content : contents) {
            if (content == null || content.amount() <= 0) {
                continue;
            }
            totals.merge(content.type(), content.amount(), Integer::sum);
        }

        List<BundleContent> aggregated = new ArrayList<>(totals.size());
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            aggregated.add(new BundleContent(entry.getKey(), entry.getValue()));
        }
        aggregated.sort(Comparator
            .comparingInt(BundleContent::amount).reversed()
            .thenComparing(BundleContent::type));
        return aggregated;
    }

    public record BundleContent(String type, int amount) {
    }
}
