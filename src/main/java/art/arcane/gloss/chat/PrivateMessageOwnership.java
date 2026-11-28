package art.arcane.gloss.chat;

import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public record PrivateMessageOwnership(boolean channels, Map<String, String> holders) {
    public static final List<String> LABELS = List.of(ChatCommands.MESSAGE_COMMAND, "tell", "w", ChatCommands.REPLY_COMMAND);

    public PrivateMessageOwnership {
        holders = holders == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(holders));
    }

    public static PrivateMessageOwnership resolve(boolean channels, Function<String, Optional<String>> holderOf) {
        Map<String, String> holders = new LinkedHashMap<>();
        if (channels) {
            for (String label : LABELS) {
                holderOf.apply(label).ifPresent(owner -> holders.put(label, owner));
            }
        }
        return new PrivateMessageOwnership(channels, holders);
    }

    public static Function<String, Optional<String>> pluginHolders(Map<String, Command> knownCommands, Plugin gloss) {
        return label -> knownCommands.get(label) instanceof PluginIdentifiableCommand command
            && command.getPlugin() != null && command.getPlugin() != gloss
            ? Optional.of(command.getPlugin().getName())
            : Optional.empty();
    }

    public static String commandFor(String label) {
        return ChatCommands.REPLY_COMMAND.equals(label) ? ChatCommands.REPLY_COMMAND : ChatCommands.MESSAGE_COMMAND;
    }

    public List<String> labels() {
        if (!channels) {
            return List.of();
        }
        List<String> labels = new ArrayList<>(LABELS.size());
        for (String label : LABELS) {
            if (!holders.containsKey(label) && !replyFollowsMessage(label)) {
                labels.add(label);
            }
        }
        return List.copyOf(labels);
    }

    public boolean sameEffect(PrivateMessageOwnership other) {
        return other != null && channels == other.channels && labels().equals(other.labels());
    }

    public String describe() {
        if (!channels) {
            return "Chat channels are off, so Gloss does not register /msg, /tell, /w or /r.";
        }
        if (holders.isEmpty()) {
            return "Gloss registers /msg, /tell, /w and /r.";
        }
        StringBuilder text = new StringBuilder(heldBy()).append(", so Gloss does not register them.");
        if (replyFollowsMessage(ChatCommands.REPLY_COMMAND)) {
            text.append(" Gloss also leaves /r alone, because its replies only follow Gloss messages.");
        }
        List<String> kept = labels();
        if (!kept.isEmpty()) {
            text.append(" Gloss registers ").append(slashes(kept)).append('.');
        }
        return text.toString();
    }

    private boolean replyFollowsMessage(String label) {
        return ChatCommands.REPLY_COMMAND.equals(label)
            && !holders.containsKey(ChatCommands.REPLY_COMMAND)
            && holders.containsKey(ChatCommands.MESSAGE_COMMAND);
    }

    private String heldBy() {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : holders.entrySet()) {
            byOwner.computeIfAbsent(entry.getValue(), owner -> new ArrayList<>()).add(entry.getKey());
        }
        List<String> parts = new ArrayList<>(byOwner.size());
        for (Map.Entry<String, List<String>> entry : byOwner.entrySet()) {
            parts.add(entry.getKey() + " already has " + slashes(entry.getValue()));
        }
        return String.join(", ", parts);
    }

    private static String slashes(List<String> labels) {
        List<String> slashed = new ArrayList<>(labels.size());
        for (String label : labels) {
            slashed.add("/" + label);
        }
        return join(slashed);
    }

    private static String join(List<String> values) {
        if (values.size() <= 1) {
            return values.isEmpty() ? "" : values.getFirst();
        }
        return String.join(", ", values.subList(0, values.size() - 1)) + " and " + values.getLast();
    }
}
