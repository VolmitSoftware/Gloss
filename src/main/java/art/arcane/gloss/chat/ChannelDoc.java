package art.arcane.gloss.chat;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One chat channel: who hears it, how it renders per viewer, and what the engine does to the text
 * before it is rendered. Selection follows the board model, so a variant replaces the base format
 * whole rather than merging into it.
 */
public record ChannelDoc(int schemaVersion, long revision, ShowCondition show, Channel channel,
                         String format, List<String> card, Mentions mentions, Items items, Links links,
                         List<Filter> filters, Throttle throttle, List<Variant> variants) {
    public static final String KIND = "channels";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_CARD_LINES = 16;
    public static final int MAX_FILTERS = 64;
    public static final int MAX_VARIANTS = 32;
    public static final int MAX_TEXT_LENGTH = 4096;

    public ChannelDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        if (channel == null) {
            throw new IllegalArgumentException("channel documents require a channel block");
        }
        format = requireText(format, "channel format");
        card = copyCard(card);
        mentions = mentions == null ? Mentions.DEFAULTS : mentions;
        items = items == null ? Items.DEFAULTS : items;
        links = links == null ? Links.DEFAULTS : links;
        filters = copyFilters(filters);
        throttle = throttle == null ? Throttle.DEFAULTS : throttle;
        variants = copyVariants(variants);
    }

    public static ChannelDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, ChannelDoc.class);
    }

    private static List<String> copyCard(List<String> card) {
        if (card == null || card.isEmpty()) {
            return List.of();
        }
        if (card.size() > MAX_CARD_LINES) {
            throw new IllegalArgumentException("a channel card may hold at most " + MAX_CARD_LINES + " lines");
        }
        List<String> copied = new ArrayList<>(card.size());
        for (String line : card) {
            copied.add(line == null ? "" : line);
        }
        return List.copyOf(copied);
    }

    private static List<Filter> copyFilters(List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("a channel may declare at most " + MAX_FILTERS + " filters");
        }
        for (int index = 0; index < filters.size(); index++) {
            Filter filter = filters.get(index);
            if (filter == null) {
                throw new IllegalArgumentException("channel filter " + index + " is null");
            }
            try {
                Pattern.compile(filter.match());
            } catch (PatternSyntaxException invalid) {
                // No cause: DocumentParsers reports the deepest message, which would drop the index.
                throw new IllegalArgumentException("channel filter " + index + " is not a valid pattern: "
                    + invalid.getDescription() + " near index " + invalid.getIndex());
            }
        }
        return List.copyOf(filters);
    }

    private static List<Variant> copyVariants(List<Variant> variants) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        if (variants.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("a channel may declare at most " + MAX_VARIANTS + " variants");
        }
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("channel variants may not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("channel variant id is duplicated: " + variant.id());
            }
        }
        return List.copyOf(variants);
    }

    static String requireText(String value, String owner) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(owner + " may not be blank");
        }
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(owner + " must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
        return normalized;
    }

    private static int clamp(Integer value, int minimum, int maximum, int fallback) {
        return value == null ? fallback : Math.clamp(value.intValue(), minimum, maximum);
    }

    /** Who hears a message and how the channel is addressed. */
    public enum Scope {
        @SerializedName(value = "global", alternate = {"GLOBAL", "party", "PARTY"})
        GLOBAL,
        @SerializedName(value = "world", alternate = {"WORLD"})
        WORLD,
        @SerializedName(value = "radius", alternate = {"RADIUS"})
        RADIUS,
        @SerializedName(value = "permission", alternate = {"PERMISSION"})
        PERMISSION,
        @SerializedName(value = "direct", alternate = {"DIRECT"})
        DIRECT
    }

    public record Channel(String name, List<String> aliases, @SerializedName("default") Boolean defaultChannel,
                          Scope scope, Integer radius, String permission, Integer priority,
                          Integer cooldownTicks) {
        public static final int MAX_ALIASES = 8;
        public static final int MAX_RADIUS = 512;

        private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

        public Channel {
            name = requireName(name, "channel name");
            aliases = copyAliases(aliases, name);
            defaultChannel = defaultChannel != null && defaultChannel;
            scope = scope == null ? Scope.GLOBAL : scope;
            permission = permission == null ? "" : permission.trim();
            radius = requireRadius(scope, radius);
            if (scope == Scope.PERMISSION && permission.isEmpty()) {
                throw new IllegalArgumentException("a permission-scoped channel requires a permission");
            }
            priority = clamp(priority, -1000, 1000, 0);
            cooldownTicks = clamp(cooldownTicks, 0, 72000, 0);
        }

        private static int requireRadius(Scope scope, Integer radius) {
            if (scope != Scope.RADIUS) {
                return radius == null ? 0 : Math.clamp(radius.intValue(), 0, MAX_RADIUS);
            }
            if (radius == null || radius <= 0) {
                throw new IllegalArgumentException("a radius-scoped channel requires a positive radius");
            }
            return Math.min(radius, MAX_RADIUS);
        }

        private static String requireName(String value, String owner) {
            String normalized = value == null ? "" : value.trim();
            if (!NAME.matcher(normalized).matches()) {
                throw new IllegalArgumentException(owner + " must match [a-z0-9][a-z0-9_-]*: " + value);
            }
            return normalized;
        }

        private static List<String> copyAliases(List<String> aliases, String name) {
            if (aliases == null || aliases.isEmpty()) {
                return List.of();
            }
            if (aliases.size() > MAX_ALIASES) {
                throw new IllegalArgumentException("a channel may declare at most " + MAX_ALIASES + " aliases");
            }
            List<String> copied = new ArrayList<>(aliases.size());
            Set<String> seen = new HashSet<>(aliases.size());
            seen.add(name);
            for (String alias : aliases) {
                String normalized = requireName(alias, "channel alias");
                if (!seen.add(normalized)) {
                    throw new IllegalArgumentException("channel alias is duplicated: " + normalized);
                }
                copied.add(normalized);
            }
            return List.copyOf(copied);
        }
    }

    public record Mentions(Boolean enabled, String pattern, String render, String sound, String permission) {
        public static final Mentions DEFAULTS = new Mentions(null, null, null, null, null);
        public static final String NAME_TOKEN = "{name}";

        public Mentions {
            enabled = enabled == null || enabled;
            pattern = pattern == null || pattern.isBlank() ? "@" + NAME_TOKEN : pattern.trim();
            if (!pattern.contains(NAME_TOKEN)) {
                throw new IllegalArgumentException("a mention pattern must contain " + NAME_TOKEN);
            }
            render = render == null || render.isBlank() ? "&e@{{ mention.name }}&r" : render;
            sound = sound == null ? "" : sound.trim();
            permission = permission == null || permission.isBlank() ? "gloss.chat.mention" : permission.trim();
        }
    }

    public record Items(Boolean enabled, String token, String permission) {
        public static final Items DEFAULTS = new Items(null, null, null);

        public Items {
            enabled = enabled == null || enabled;
            token = token == null || token.isBlank() ? "[item]" : token.trim();
            permission = permission == null || permission.isBlank() ? "gloss.chat.item" : permission.trim();
        }
    }

    public record Links(Boolean enabled, String render) {
        public static final Links DEFAULTS = new Links(null, null);

        public Links {
            enabled = enabled == null || enabled;
            render = render == null || render.isBlank() ? "&9&n{{ link.host }}" : render;
        }
    }

    public record Filter(String match, String replace) {
        public Filter {
            if (match == null || match.isBlank()) {
                throw new IllegalArgumentException("a channel filter requires a match pattern");
            }
            replace = replace == null ? "" : replace;
        }
    }

    public record Throttle(Integer repeatWindowTicks, Integer maxRepeats, Integer minIntervalTicks) {
        public static final Throttle DEFAULTS = new Throttle(null, null, null);

        public Throttle {
            repeatWindowTicks = clamp(repeatWindowTicks, 0, 72000, 100);
            maxRepeats = clamp(maxRepeats, 1, 64, 1);
            minIntervalTicks = clamp(minIntervalTicks, 0, 72000, 10);
        }
    }

    public record Variant(String id, Integer priority, String when, String format) {
        public Variant {
            id = normalizeId(id);
            when = normalizeCondition(when, "channel variant " + id);
            ConditionCompiler.compile(new ConditionSource("channels.variants." + id + ".when", when));
            priority = clamp(priority, -1000, 1000, 0);
            format = requireText(format, "channel variant " + id + " format");
        }

        private static String normalizeId(String id) {
            String normalized = id == null ? "" : id.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("channel variant id may not be blank");
            }
            for (int index = 0; index < normalized.length(); index++) {
                char character = normalized.charAt(index);
                if (!Character.isLetterOrDigit(character) && character != '-' && character != '_'
                    && character != '.') {
                    throw new IllegalArgumentException("channel variant id contains an unsupported character: " + id);
                }
            }
            return normalized;
        }

        private static String normalizeCondition(String when, String owner) {
            String normalized = when == null ? "" : when.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(owner + " condition may not be blank");
            }
            return normalized;
        }
    }
}
