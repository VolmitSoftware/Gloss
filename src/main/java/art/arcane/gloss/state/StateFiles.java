package art.arcane.gloss.state;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * The on-disk shape of one state file: a JSON object of key to value, where a value is a number,
 * a string, a boolean, or an object holding another lane's section. The store is the only writer;
 * a file that does not parse is renamed {@code .corrupt-<millis>} and treated as empty so a broken
 * file never blocks a player from joining.
 */
public final class StateFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type OBJECT = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private StateFiles() {
    }

    public static Map<String, Object> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("state file " + file + " could not be read", failure);
        }
        try {
            Map<String, Object> values = GSON.fromJson(raw, OBJECT);
            if (values == null) {
                return new LinkedHashMap<>();
            }
            return values;
        } catch (JsonParseException | IllegalStateException failure) {
            setAside(file, failure);
            return new LinkedHashMap<>();
        }
    }

    public static void write(Path file, Map<String, Object> values) {
        byte[] content = GSON.toJson(values, OBJECT).getBytes(StandardCharsets.UTF_8);
        try {
            AtomicFiles.replace(file, content);
        } catch (IOException failure) {
            throw new UncheckedIOException("state file " + file + " could not be written", failure);
        }
    }

    private static void setAside(Path file, RuntimeException failure) {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            Gloss.log(Level.WARNING, "State file %s did not parse (%s); moved to %s and starting empty.",
                file.getFileName(), failure.getMessage(), target.getFileName());
        } catch (IOException moveFailure) {
            throw new UncheckedIOException("corrupt state file " + file + " could not be set aside", moveFailure);
        }
    }
}
