package art.arcane.gloss.chat;

import java.util.List;

/** Channel fixtures the filter, throttle and body suites share. */
final class ChatTestChannels {
    private ChatTestChannels() {
    }

    static ChannelRuntime runtime(ChannelDoc.Mentions mentions, ChannelDoc.Items items,
                                  ChannelDoc.Links links, List<ChannelDoc.Filter> filters,
                                  ChannelDoc.Throttle throttle) {
        ChannelDoc doc = new ChannelDoc(ChannelDoc.CURRENT_SCHEMA_VERSION, 1L, null,
            new ChannelDoc.Channel("global", List.of(), null, null, null, null, null, null),
            "&f{{ sender.name }}&8: &f{{ message }}", List.of(), mentions, items, links,
            filters, throttle, List.of());
        return ChannelRuntime.of("global", doc);
    }

    static ChannelRuntime plain() {
        return runtime(null, null, null, List.of(), null);
    }
}
