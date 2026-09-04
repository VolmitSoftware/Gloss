package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropShowConfigTest {
    @Test
    void tomlBooleanAndExpressionCompileAndRoundTrip() throws IOException {
        GlossConfigFile hidden = TomlCodec.fromToml("[drops]\nshow = false\n", GlossConfigFile.class);
        hidden.normalize();
        assertFalse(GlossConfig.from(hidden).drops().show().isAlwaysVisible());
        assertFalse(GlossConfig.from(hidden).drops().show().isDynamic());
        GlossConfigFile dynamic = TomlCodec.fromToml(
            "[drops]\nshow = \"{{ world.time > 12000 }}\"\n", GlossConfigFile.class);
        dynamic.normalize();
        String written = TomlCodec.toToml(dynamic, "gloss", ConfigExposePolicy.ALL);
        GlossConfigFile roundTrip = TomlCodec.fromToml(written, GlossConfigFile.class);
        roundTrip.normalize();
        assertTrue(GlossConfig.from(roundTrip).drops().show().isDynamic());
        assertEquals("world.time > 12000", GlossConfig.from(roundTrip).drops().show().expression());
    }

    @Test
    void invalidVisibilityFailsDuringNormalization() {
        GlossConfigFile source = new GlossConfigFile();
        source.drops.show = "42";
        assertThrows(IllegalArgumentException.class, source::normalize);
    }
}
