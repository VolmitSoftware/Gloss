package art.arcane.gloss.importer;

import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.behavior.BehaviorDoc;
import art.arcane.gloss.bubble.BubbleStyleDoc;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.drop.RealDropSettingsDoc;
import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.hologram.HologramMath;
import art.arcane.gloss.indicator.DamageIndicatorSettingsDoc;
import art.arcane.gloss.tab.TablistDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class VersionedGlossConverter {
    private VersionedGlossConverter() {
    }

    public static Conversion convert(String kind, String id, JsonObject source) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
        int current = currentVersion(kind);
        int version = version(source);
        if (version < 1 || version > current) {
            throw new UnsupportedFormatException("Unsupported " + kind + " schemaVersion " + version);
        }
        JsonObject document = source.deepCopy();
        List<String> warnings = new ArrayList<>();
        if (kind.equals("behaviors")) {
            return BehaviorSchemaConverter.convert(id, document);
        }
        if (kind.equals("tablist")) {
            if (version == 1) {
                tablist(document);
                document.addProperty("schemaVersion", 2);
            }
            TablistSchemaConverter.Conversion conversion = TablistSchemaConverter.convert(id, document);
            return new Conversion(conversion.document(), conversion.warnings());
        }
        if (version == current) {
            validate(kind, id, document);
            return new Conversion(document, warnings);
        }
        switch (kind) {
            case "holograms" -> hologram(document);
            case "bubbles" -> bubble(document, version, warnings);
            case "boards" -> board(document, id, warnings);
            case "damage-indicators" -> indicator(document, version);
            case "real-drops" -> drops(document, version);
            case "entity-overlays" -> overlay(document, warnings);
            default -> throw new UnsupportedFormatException("Unsupported document kind: " + kind);
        }
        document.addProperty("schemaVersion", current);
        JsonObject canonical = DocumentParsers.GSON.toJsonTree(validate(kind, id, document)).getAsJsonObject();
        return new Conversion(canonical, warnings);
    }

    private static int currentVersion(String kind) {
        return switch (kind) {
            case "behaviors" -> BehaviorDoc.CURRENT_SCHEMA_VERSION;
            case "holograms" -> HologramDoc.CURRENT_SCHEMA_VERSION;
            case "bubbles" -> BubbleStyleDoc.CURRENT_SCHEMA_VERSION;
            case "boards" -> BoardDoc.CURRENT_SCHEMA_VERSION;
            case "tablist" -> TablistDoc.CURRENT_SCHEMA_VERSION;
            case "damage-indicators" -> DamageIndicatorSettingsDoc.CURRENT_SCHEMA_VERSION;
            case "real-drops" -> RealDropSettingsDoc.CURRENT_SCHEMA_VERSION;
            case "entity-overlays" -> EntityOverlayDoc.CURRENT_SCHEMA_VERSION;
            default -> throw new UnsupportedFormatException("Unsupported document kind: " + kind);
        };
    }

    private static int version(JsonObject source) {
        JsonElement value = source.get("schemaVersion");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new UnsupportedFormatException("Unsupported schemaVersion: expected an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new UnsupportedFormatException("Unsupported schemaVersion: expected an integer", failure);
        }
    }

    private static Object validate(String kind, String id, JsonObject document) {
        String file = kind + "/" + id + ".json";
        String raw = document.toString();
        return switch (kind) {
            case "behaviors" -> BehaviorDoc.parse(file, raw);
            case "holograms" -> HologramDoc.parse(file, raw);
            case "bubbles" -> BubbleStyleDoc.parse(file, raw);
            case "boards" -> BoardDoc.parse(file, raw);
            case "tablist" -> TablistDoc.parse(file, raw);
            case "damage-indicators" -> DamageIndicatorSettingsDoc.parse(file, raw);
            case "real-drops" -> RealDropSettingsDoc.parse(file, raw);
            case "entity-overlays" -> EntityOverlayDoc.parse(file, raw);
            default -> throw new UnsupportedFormatException("Unsupported document kind: " + kind);
        };
    }

    private static void hologram(JsonObject document) {
        double scale = number(document, "scale", 1);
        if (scale < 0.05 || scale > 16) {
            throw new IllegalArgumentException("Historical hologram scale must be within 0.05..16");
        }
        normalizeLines(document);
        JsonObject style = uniformStyle(scale);
        style.addProperty("billboard", billboard(document));
        style.addProperty("seeThrough", bool(document, "seeThrough", true));
        document.add("style", style);
        remove(document, "scale", "billboard", "seeThrough");
    }

    private static void bubble(JsonObject document, int version, List<String> warnings) {
        if (version == 1) {
            JsonObject translation = new JsonObject();
            translation.addProperty("y", bool(document, "flyAway", false) ? BubbleStyleDoc.DEFAULT_TRANSLATION_Y : "0");
            JsonObject motion = new JsonObject();
            motion.add("translation", translation);
            document.add("motion", motion);
            JsonObject shimmer = new JsonObject();
            shimmer.addProperty("spawn", false);
            shimmer.addProperty("flyAway", false);
            document.add("shimmer", shimmer);
            if (!present(document, "offset")) {
                JsonArray offset = new JsonArray();
                offset.add(0);
                offset.add(1);
                offset.add(0);
                document.add("offset", offset);
            }
            warnings.add("Approximate: schema 1 spawned wrapped lines independently with lineStaggerTicks; current bubbles display each message as one block without line staggering.");
            remove(document, "flyAway", "lineStaggerTicks");
        }
        if (version == 2 && !present(document, "offset")) {
            warnings.add("Approximate: schema 2 changed its omitted offset default; the latest schema 2 default of [0, 0.3, 0] is used.");
        }
        if (version == 2 && !present(document, "shimmer")) {
            warnings.add("Approximate: schema 2 changed its omitted shimmer timing defaults; the latest schema 2 defaults are used.");
        }
        if (version <= 2 && present(document, "select")) {
            JsonObject old = document.getAsJsonObject("select");
            String worlds = alternatives(old, "worlds", "subject.world", true);
            String groups = alternatives(old, "groups", "subject", false);
            JsonObject select = new JsonObject();
            select.addProperty("priority", integer(old, "priority", 0));
            select.addProperty("when", "(" + worlds + ") && (" + groups + ")");
            document.add("select", select);
        }
    }

    private static void board(JsonObject document, String id, List<String> warnings) {
        normalizeLines(document);
        String permission = string(document, "permission", "default").trim().toLowerCase(Locale.ROOT);
        boolean gated = !permission.isEmpty() && !permission.equals("default");
        boolean primary = bool(document, "primary", false);
        String groups = alternatives(document, "groups", "viewer", false);
        boolean grouped = !strings(document, "groups").isEmpty();
        String permitted = gated ? "hasPermission('viewer', " + quote("gloss.board." + permission) + ")" : "true";
        String selected = gated || primary ? permitted : grouped ? "(" + groups + ") && (" + permitted + ")" : "false";
        JsonObject select = new JsonObject();
        select.addProperty("priority", grouped ? 200 : gated ? 100 : 0);
        select.addProperty("when", selected);
        document.add("select", select);
        JsonObject presentation = new JsonObject();
        String title = string(document, "title", "");
        presentation.addProperty("title", title.isBlank() ? id : title);
        move(document, presentation, "lines", "hideNumbers");
        document.add("presentation", presentation);
        if (grouped && (gated || primary)) {
            warnings.add("Approximate: this board combines group selection with permission/primary fallback. Its fixed priority cannot preserve every cross-board fallback ordering; review boards together.");
        }
        remove(document, "title", "primary", "permission", "groups");
    }

    private static void tablist(JsonObject document) {
        JsonObject headerPresentation = new JsonObject();
        headerPresentation.addProperty("header", string(document, "header", ""));
        headerPresentation.addProperty("footer", string(document, "footer", ""));
        JsonObject header = new JsonObject();
        header.addProperty("enabled", bool(document, "useHeaderFooter", false));
        header.add("presentation", headerPresentation);
        document.add("headerFooter", header);
        Map<String, String> formats = new LinkedHashMap<>();
        if (present(document, "nameFormats")) {
            for (Map.Entry<String, JsonElement> entry : document.getAsJsonObject("nameFormats").entrySet()) {
                if (entry.getKey().isBlank()) {
                    continue;
                }
                formats.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString());
            }
        }
        JsonObject names = new JsonObject();
        names.addProperty("enabled", bool(document, "groupListNames", false));
        names.add("presentation", nameFormat(formats.getOrDefault("default", "$player")));
        JsonArray variants = new JsonArray();
        for (Map.Entry<String, String> format : formats.entrySet()) {
            String group = format.getKey();
            if (group.equals("default")) {
                continue;
            }
            boolean operator = group.equals("_op");
            JsonObject variant = new JsonObject();
            variant.addProperty("id", "group-" + variants.size());
            variant.addProperty("priority", operator ? 100 : 0);
            variant.addProperty("when", operator ? "subject.op" : "inGroup('subject', " + quote(group) + ")");
            variant.add("presentation", nameFormat(operator ? format.getValue().replace("$group", "_op") : format.getValue()));
            variants.add(variant);
            if (operator) {
                JsonObject groupVariant = variant.deepCopy();
                groupVariant.addProperty("id", "group-" + variants.size());
                groupVariant.addProperty("priority", 0);
                groupVariant.addProperty("when", "inGroup('subject', '_op')");
                groupVariant.add("presentation", nameFormat(format.getValue()));
                variants.add(groupVariant);
            }
        }
        names.add("variants", variants);
        document.add("listNames", names);
        remove(document, "header", "footer", "useHeaderFooter", "groupListNames", "nameFormats");
    }

    private static JsonObject nameFormat(String format) {
        JsonObject presentation = new JsonObject();
        presentation.addProperty("format", format);
        return presentation;
    }

    private static void indicator(JsonObject document, int version) {
        if (version != 1) {
            return;
        }
        for (String name : List.of("damage", "healing")) {
            if (!present(document, name)) {
                continue;
            }
            JsonObject old = document.getAsJsonObject(name);
            JsonObject presentation = new JsonObject();
            move(old, presentation, "format", "offset", "motion");
            if (present(old, "presentation")) {
                presentation.add("transform", old.remove("presentation"));
            }
            JsonObject style = new JsonObject();
            style.addProperty("when", bool(old, "enabled", true) ? "true" : "false");
            style.add("presentation", presentation);
            document.add(name, style);
        }
        if (present(document, "filters")) {
            JsonObject filters = document.getAsJsonObject("filters");
            List<String> disabled = strings(filters, "disabledWorlds");
            if (!disabled.isEmpty()) {
                List<String> conditions = new ArrayList<>();
                for (String world : disabled) {
                    conditions.add("subject.world != " + quote(world));
                }
                document.addProperty("show", String.join(" && ", conditions));
            }
            document.remove("filters");
        }
    }

    private static void drops(JsonObject document, int version) {
        if (version == 1) {
            JsonObject presentation = new JsonObject();
            move(document, presentation, "limits", "scale", "motion", "landing", "labels", "filters", "physics", "script", "animation");
            document.add("presentation", presentation);
        }
        if (present(document, "presentation")) {
            dropLabels(document.getAsJsonObject("presentation"));
        }
        if (present(document, "variants")) {
            for (JsonElement entry : document.getAsJsonArray("variants")) {
                JsonObject variant = entry.getAsJsonObject();
                if (present(variant, "presentation")) {
                    dropLabels(variant.getAsJsonObject("presentation"));
                }
            }
        }
    }

    private static void dropLabels(JsonObject presentation) {
        if (!present(presentation, "labels")) {
            return;
        }
        JsonObject labels = presentation.getAsJsonObject("labels");
        JsonObject style = uniformStyle(Math.clamp(number(labels, "scale", 0.85), 0.1, 4));
        style.addProperty("billboard", billboard(labels));
        style.addProperty("seeThrough", bool(labels, "seeThrough", true));
        style.addProperty("shadow", bool(labels, "shadow", true));
        style.addProperty("viewRange", HologramMath.viewRangeMultiplier(Math.clamp(number(labels, "viewRange", 32), 4, 128)));
        int argb = bool(labels, "background", true)
            ? channel(labels, "backgroundAlpha", 80) << 24 | channel(labels, "backgroundRed", 0) << 16
                | channel(labels, "backgroundGreen", 0) << 8 | channel(labels, "backgroundBlue", 0)
            : 0;
        style.addProperty("backgroundArgb", String.format(Locale.ROOT, "#%08X", argb));
        labels.add("style", style);
        labels.addProperty("yOffset", Math.clamp(number(labels, "yOffset", 0.55), 0, 4));
        remove(labels, "scale", "viewRange", "billboard", "seeThrough", "shadow", "background", "backgroundAlpha", "backgroundRed", "backgroundGreen", "backgroundBlue");
    }

    private static int channel(JsonObject labels, String name, int fallback) {
        return Math.clamp(integer(labels, name, fallback), 0, 255);
    }

    private static void overlay(JsonObject document, List<String> warnings) {
        boolean names = bool(document, "showNames", true);
        String named = names ? "entity.named" : "false";
        String stacked = "entity.stackCount > 1";
        String suffix = string(document, "stackFormat", " &7x{count}");
        String name = string(document, "nameFormat", "&f{name}");
        String health = bool(document, "showHealthNumbers", true)
            ? string(document, "healthFormat", "{bar} &f{health}&7/{max_health}") : "{bar}";
        JsonArray lines = new JsonArray();
        if (names) {
            lines.add(line("name-stacked", "text", name + suffix, named + " && " + stacked));
            lines.add(line("name", "text", name, named + " && !(" + stacked + ")"));
        }
        lines.add(line("health-stacked", "text", health + suffix, "!(" + named + ") && " + stacked));
        lines.add(line("health", "text", health, named + " || !(" + stacked + ")"));
        String damage = string(document, "damageFormat", "&c-{damage}");
        if (!damage.isBlank()) {
            lines.add(line("damage", "text", damage, "entity.damaged"));
        }
        lines.add(line("insight", "insight", "{insight}", "true"));
        String stats = string(document, "statsFormat", "&7ATK &f{attack} &8| &7ARM &f{armor}");
        if (bool(document, "showCombatStats", true) && !stats.isBlank()) {
            lines.add(line("stats", "text", stats, "true"));
        }
        document.add("lines", lines);
        document.add("style", uniformStyle(Math.clamp(number(document, "scale", 0.75), 0.1, 4)));
        if (!present(document, "maxEntitiesPerViewer")) {
            document.addProperty("maxEntitiesPerViewer", 64);
        }
        warnings.add("Approximate: historical entity overlays had no global active-overlay limit; the current maxActiveOverlays default applies.");
        remove(document, "scale", "showNames", "showHealthNumbers", "showCombatStats", "nameFormat", "healthFormat", "stackFormat", "statsFormat", "damageFormat");
    }

    private static JsonObject line(String id, String type, String text, String show) {
        JsonObject line = new JsonObject();
        line.addProperty("id", id);
        line.addProperty("type", type);
        line.addProperty("text", text);
        line.addProperty("show", show);
        return line;
    }

    private static String billboard(JsonObject object) {
        String value = string(object, "billboard", "center").trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? "center" : value;
    }

    private static void normalizeLines(JsonObject document) {
        if (!present(document, "lines")) {
            return;
        }
        JsonArray lines = document.getAsJsonArray("lines");
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).isJsonNull()) {
                lines.set(index, new JsonPrimitive(""));
            }
        }
    }

    private static JsonObject uniformStyle(double scale) {
        JsonObject style = new JsonObject();
        style.addProperty("billboard", "center");
        style.addProperty("seeThrough", true);
        style.addProperty("scaleX", scale);
        style.addProperty("scaleY", scale);
        style.addProperty("scaleZ", scale);
        return style;
    }

    private static String alternatives(JsonObject object, String key, String role, boolean worlds) {
        List<String> conditions = new ArrayList<>();
        for (String value : strings(object, key)) {
            conditions.add(worlds ? "matchesGlob(" + role + ", " + quote(value) + ")"
                : "inGroup(" + quote(role) + ", " + quote(value.toLowerCase(Locale.ROOT)) + ")");
        }
        return conditions.isEmpty() ? "true" : String.join(" || ", conditions);
    }

    private static List<String> strings(JsonObject object, String key) {
        if (!present(object, key)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement entry : object.getAsJsonArray(key)) {
            if (!entry.isJsonNull() && !entry.getAsString().isBlank()) {
                values.add(entry.getAsString().trim());
            }
        }
        return values;
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static boolean present(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }

    private static String string(JsonObject object, String key, String fallback) {
        return present(object, key) ? object.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return present(object, key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static double number(JsonObject object, String key, double fallback) {
        double value = present(object, key) ? object.get(key).getAsDouble() : fallback;
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(key + " must be finite");
        }
        return value;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return present(object, key) ? object.get(key).getAsInt() : fallback;
    }

    private static void move(JsonObject from, JsonObject to, String... keys) {
        for (String key : keys) {
            if (from.has(key)) {
                to.add(key, from.remove(key));
            }
        }
    }

    private static void remove(JsonObject object, String... keys) {
        for (String key : keys) {
            object.remove(key);
        }
    }

    public static final class UnsupportedFormatException extends IllegalArgumentException {
        public UnsupportedFormatException(String message) {
            super(message);
        }

        public UnsupportedFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record Conversion(JsonObject document, List<String> warnings) {
        public Conversion {
            document = Objects.requireNonNull(document, "document");
            warnings = List.copyOf(warnings);
        }
    }
}
