package art.arcane.gloss.lint;

import java.util.regex.Pattern;

/** A {@code :name:} token with no emoji document behind it stays literal in chat. */
public final class EmojiRule extends TextTokenRule {
    public static final String CODE = "emoji-unknown";
    private static final Pattern TOKEN = Pattern.compile(":([a-z0-9_-]{2,32}):");

    public EmojiRule() {
        super(TOKEN);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    boolean resolves(LintContext context, String captured) {
        return context.ids("emoji").contains(captured);
    }

    @Override
    boolean applicable(LintContext context) {
        return context.hasKind("emoji");
    }

    @Override
    Severity severity() {
        return Severity.WARNING;
    }

    @Override
    String message(String captured) {
        return "emoji is not loaded: " + captured;
    }
}
