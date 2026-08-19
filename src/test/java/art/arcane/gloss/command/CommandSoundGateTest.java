package art.arcane.gloss.command;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSoundGateTest {
    private static GlossConfig config(boolean sounds) {
        GlossConfigFile file = new GlossConfigFile();
        file.commands.sounds = sounds;
        file.normalize();
        return GlossConfig.from(file);
    }

    @Test
    void soundsAreOnByDefault() {
        assertTrue(GlossCommandService.soundsEnabled(config(true)));
        assertTrue(GlossCommandService.commandSoundsEnabled());
    }

    @Test
    void disablingCommandSoundsSilencesEveryChime() {
        assertFalse(GlossCommandService.soundsEnabled(config(false)));
    }

    @Test
    void anAbsentConfigurationStillPlaysChimes() {
        assertTrue(GlossCommandService.soundsEnabled(null));
    }
}
