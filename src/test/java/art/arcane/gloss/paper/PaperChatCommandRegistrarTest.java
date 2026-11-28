package art.arcane.gloss.paper;

import art.arcane.gloss.chat.ChatCommands;
import art.arcane.gloss.chat.PrivateMessageOwnership;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperChatCommandRegistrarTest {

    @Test
    void channelsOffRegistersOnlyTheChannelCommand() {
        List<PaperChatCommandRegistrar.Registration> registrations = PaperChatCommandRegistrar.registrations(
            ownership(false, Map.of()));

        assertEquals(List.of("ch"), labels(registrations));
    }

    @Test
    void withNoOtherPluginEveryLabelRegistersAsAMainLabel() {
        List<PaperChatCommandRegistrar.Registration> registrations = PaperChatCommandRegistrar.registrations(
            ownership(true, Map.of()));

        assertEquals(List.of("msg", "tell", "w", "r", "ch"), labels(registrations));
        assertEquals(ChatCommands.MESSAGE_COMMAND, registrations.get(1).command());
        assertEquals(ChatCommands.MESSAGE_COMMAND, registrations.get(2).command());
        assertEquals(ChatCommands.REPLY_COMMAND, registrations.get(3).command());
    }

    @Test
    void anotherPluginsLabelsLeaveOnlyTheChannelCommand() {
        List<PaperChatCommandRegistrar.Registration> registrations = PaperChatCommandRegistrar.registrations(
            ownership(true, Map.of("msg", "Foundation", "tell", "Foundation", "w", "Foundation", "r", "Foundation")));

        assertEquals(List.of("ch"), labels(registrations));
    }

    private static PrivateMessageOwnership ownership(boolean channels, Map<String, String> holders) {
        return PrivateMessageOwnership.resolve(channels, label -> Optional.ofNullable(holders.get(label)));
    }

    private static List<String> labels(List<PaperChatCommandRegistrar.Registration> registrations) {
        List<String> labels = new ArrayList<>(registrations.size());
        for (PaperChatCommandRegistrar.Registration registration : registrations) {
            labels.add(registration.label());
        }
        return labels;
    }
}
