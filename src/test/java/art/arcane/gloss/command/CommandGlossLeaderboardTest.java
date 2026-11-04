package art.arcane.gloss.command;

import art.arcane.gloss.leaderboard.LeaderboardView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandGlossLeaderboardTest {
    private static final char SECTION = '§';

    @Test
    public void rowsWithSectionCodesParseAsMiniMessage() {
        String name = SECTION + "r" + SECTION + "fSteve" + SECTION + "r";
        String formatted = SECTION + "a12.0h";
        String line = CommandGlossLeaderboard.rowLine(1, new LeaderboardView.Row(UUID.randomUUID(), name, 12.0D, formatted));

        assertFalse(line.indexOf(SECTION) >= 0);
        Component parsed = MiniMessage.miniMessage().deserialize(line);
        assertEquals("1. Steve 12.0h", PlainTextComponentSerializer.plainText().serialize(parsed));
    }

    @Test
    public void rowColorsReachThePlayer() {
        String line = CommandGlossLeaderboard.rowLine(2,
            new LeaderboardView.Row(UUID.randomUUID(), SECTION + "cAlex", 3.0D, SECTION + "a3"));

        List<TextColor> colors = new ArrayList<>();
        collectColors(MiniMessage.miniMessage().deserialize(line), colors);
        assertTrue(colors.contains(NamedTextColor.GRAY));
        assertTrue(colors.contains(NamedTextColor.RED));
        assertTrue(colors.contains(NamedTextColor.GREEN));
    }

    @Test
    public void unknownSectionCodesDoNotFailTheLine() {
        String line = CommandGlossLeaderboard.rowLine(3,
            new LeaderboardView.Row(UUID.randomUUID(), SECTION + "zSam", 1.0D, "1" + SECTION));

        assertEquals("3. " + SECTION + "zSam 1" + SECTION,
            PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(line)));
    }

    private static void collectColors(Component component, List<TextColor> colors) {
        if (component.color() != null) {
            colors.add(component.color());
        }
        for (Component child : component.children()) {
            collectColors(child, colors);
        }
    }
}
