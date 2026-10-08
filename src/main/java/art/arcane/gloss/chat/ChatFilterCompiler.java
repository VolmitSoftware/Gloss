package art.arcane.gloss.chat;

import com.google.re2j.Pattern;
import art.arcane.gloss.text.BoundedRegexCompiler;

import java.util.List;

public final class ChatFilterCompiler {
    private ChatFilterCompiler() {
    }

    public static void validate(List<ChannelDoc.Filter> filters, ChannelDoc.Filtering limits) {
        if (filters.size() > limits.maxFilters()) {
            throw new IllegalArgumentException("channel filters exceed filtering.maxFilters " + limits.maxFilters());
        }
        for (int index = 0; index < filters.size(); index++) {
            compile(filters.get(index), limits, index);
        }
    }

    public static ChannelRuntime.CompiledFilter compile(ChannelDoc.Filter filter, ChannelDoc.Filtering limits,
                                                        int index) {
        String owner = "channel filter " + index;
        if (filter.match().length() > limits.maxPatternCharacters()) {
            throw new IllegalArgumentException(owner + " exceeds filtering.maxPatternCharacters");
        }
        if (filter.replace().length() > limits.maxReplacementCharacters()) {
            throw new IllegalArgumentException(owner + " exceeds filtering.maxReplacementCharacters");
        }
        Pattern pattern = BoundedRegexCompiler.compile(filter.match(),
            new BoundedRegexCompiler.Limits(limits.maxPatternCharacters(), limits.maxProgramSize(),
                limits.maxNestingDepth()), owner);
        return new ChannelRuntime.CompiledFilter(pattern, filter.replace());
    }
}
