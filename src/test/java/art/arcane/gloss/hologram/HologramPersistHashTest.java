package art.arcane.gloss.hologram;

import art.arcane.volmlib.util.io.IO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HologramPersistHashTest {
    @TempDir
    File folder;

    @Test
    void writeAllReadAllRoundTripAppendsSingleTrailingNewline() throws IOException {
        String json = new HologramDescriptor("shop", "world", 1.5D, 70.0D, -3.0D, List.of("&7Buy here", "&dOpen daily"))
            .toJson()
            .toString(4);
        File file = new File(folder, "shop.json");

        IO.writeAll(file, json);

        assertEquals(json + "\n", IO.readAll(file));
    }

    @Test
    void ownWriteHashMatchesReadBackContent() throws IOException {
        String json = new HologramDescriptor("arena", "world_nether", 0.0D, -32.5D, 1000000.125D, List.of("plain", "", "line"))
            .toJson()
            .toString(4);
        File file = new File(folder, "arena.json");
        String expected = IO.hash(json + "\n");

        IO.writeAll(file, json);

        assertEquals(expected, IO.hash(IO.readAll(file)));
    }

    @Test
    void singleLineContentRoundTripsWithTrailingNewline() throws IOException {
        String json = "{\"id\":\"flat\"}";
        File file = new File(folder, "flat.json");

        IO.writeAll(file, json);

        assertEquals(IO.hash(json + "\n"), IO.hash(IO.readAll(file)));
    }
}
