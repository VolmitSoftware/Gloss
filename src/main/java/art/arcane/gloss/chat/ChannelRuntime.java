package art.arcane.gloss.chat;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A loaded {@link ChannelDoc} with everything the per-message path would otherwise recompute:
 * filter patterns, the mention scanner and the variant conditions, sorted highest priority first
 * with the id as the stable tie-breaker.
 */
public final class ChannelRuntime {
    public static final String LINK_GROUP = "link";
    public static final String MENTION_GROUP = "mention";
    public static final String MENTION_NAME_GROUP = "mentionname";
    public static final String ITEM_GROUP = "item";

    private static final String LINK_BODY = "https?://[^\\s<>]+|www\\.[^\\s<>]+";
    private static final String MENTION_NAME_BODY = "[A-Za-z0-9_]{1,16}";

    private static final Comparator<ChannelDoc.Variant> BY_PRIORITY_THEN_ID =
        Comparator.comparingInt((ChannelDoc.Variant variant) -> -variant.priority())
            .thenComparing(ChannelDoc.Variant::id);

    private final String id;
    private final ChannelDoc doc;
    private final List<CompiledFilter> filters;
    private final List<CompiledVariant> variants;
    private final Pattern bodyScanner;

    private ChannelRuntime(String id, ChannelDoc doc, List<CompiledFilter> filters,
                           List<CompiledVariant> variants, Pattern bodyScanner) {
        this.id = id;
        this.doc = doc;
        this.filters = filters;
        this.variants = variants;
        this.bodyScanner = bodyScanner;
    }

    public static ChannelRuntime of(String id, ChannelDoc doc) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(doc, "doc");
        List<CompiledFilter> filters = new ArrayList<>(doc.filters().size());
        for (ChannelDoc.Filter filter : doc.filters()) {
            filters.add(new CompiledFilter(Pattern.compile(filter.match()), filter.replace()));
        }
        List<ChannelDoc.Variant> ordered = new ArrayList<>(doc.variants());
        ordered.sort(BY_PRIORITY_THEN_ID);
        List<CompiledVariant> variants = new ArrayList<>(ordered.size());
        for (ChannelDoc.Variant variant : ordered) {
            variants.add(new CompiledVariant(variant, ConditionCompiler.compile(
                new ConditionSource("channels." + id + ".variants." + variant.id() + ".when", variant.when()))));
        }
        return new ChannelRuntime(id, doc, List.copyOf(filters), List.copyOf(variants), bodyScanner(doc));
    }

    public String id() {
        return id;
    }

    public ChannelDoc doc() {
        return doc;
    }

    public String name() {
        return doc.channel().name();
    }

    public List<String> aliases() {
        return doc.channel().aliases();
    }

    public ChannelDoc.Scope scope() {
        return doc.channel().scope();
    }

    public int priority() {
        return doc.channel().priority();
    }

    public boolean isDefault() {
        return doc.channel().defaultChannel();
    }

    public List<CompiledFilter> filters() {
        return filters;
    }

    /** One left-to-right pass over the message body: links, then mentions, then the item token. */
    public Pattern bodyScanner() {
        return bodyScanner;
    }

    /** The variant whose condition matches first, or null when the base format applies. */
    public ChannelDoc.Variant activeVariant(ExprScope scope, BoundedConditionErrorCallback errors) {
        for (CompiledVariant variant : variants) {
            if (variant.condition().matches(scope, errors)) {
                return variant.variant();
            }
        }
        return null;
    }

    public String format(ExprScope scope, BoundedConditionErrorCallback errors) {
        ChannelDoc.Variant variant = activeVariant(scope, errors);
        return variant == null ? doc.format() : variant.format();
    }

    /**
     * The authored template ({@code @{name}}) becomes a scanner whose one group is the name. The
     * literal halves are quoted so a template may carry regex metacharacters safely.
     */
    private static Pattern bodyScanner(ChannelDoc doc) {
        String mention = mentionBody(doc.mentions().pattern(),
            "(?<" + MENTION_NAME_GROUP + ">" + MENTION_NAME_BODY + ")");
        return Pattern.compile("(?<" + LINK_GROUP + ">" + LINK_BODY + ")"
            + "|(?<" + MENTION_GROUP + ">" + mention + ")"
            + "|(?<" + ITEM_GROUP + ">" + Pattern.quote(doc.items().token()) + ")");
    }

    private static String mentionBody(String template, String nameGroup) {
        int token = template.indexOf(ChannelDoc.Mentions.NAME_TOKEN);
        return Pattern.quote(template.substring(0, token)) + nameGroup
            + Pattern.quote(template.substring(token + ChannelDoc.Mentions.NAME_TOKEN.length()));
    }

    public record CompiledFilter(Pattern pattern, String replace) {
    }

    private record CompiledVariant(ChannelDoc.Variant variant, CompiledCondition condition) {
    }
}
