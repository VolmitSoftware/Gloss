package art.arcane.gloss.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code pack_format} number {@code pack.mcmeta} carries, per server version. The client owns
 * this number (its {@code version.json} calls it {@code resource_pack_version}); the shipped table
 * is what the plugin knows, and {@code [forge] packFormat} is how an operator corrects it without
 * waiting for a release.
 */
public final class PackFormats {
    private static final String RESOURCE = "/forge/pack-formats.json";
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    private final String source;
    private final NavigableMap<Key, Integer> formats;

    private PackFormats(String source, NavigableMap<Key, Integer> formats) {
        this.source = source;
        this.formats = formats;
    }

    private record Key(int major, int minor) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            return major != other.major ? Integer.compare(major, other.major)
                : Integer.compare(minor, other.minor);
        }
    }

    public static PackFormats load() {
        try (InputStream stream = PackFormats.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            JsonObject root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            NavigableMap<Key, Integer> formats = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("formats").entrySet()) {
                Key key = key(entry.getKey());
                if (key != null) {
                    formats.put(key, entry.getValue().getAsInt());
                }
            }
            if (formats.isEmpty()) {
                throw new IllegalStateException(RESOURCE + " declares no formats");
            }
            return new PackFormats(root.get("source").getAsString(), formats);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Where the shipped numbers come from, printed by {@code /gloss forge status}. */
    public String source() {
        return source;
    }

    /** The newest format the table knows, used for server versions newer than the table. */
    public int newest() {
        return formats.lastEntry().getValue();
    }

    /**
     * @param bukkitVersion {@code Bukkit.getBukkitVersion()}, for example {@code 26.2-R0.1-SNAPSHOT}
     * @param override {@code [forge] packFormat}; anything above zero wins outright
     */
    public int forServer(String bukkitVersion, int override) {
        if (override > 0) {
            return override;
        }
        Key key = key(bukkitVersion);
        if (key == null) {
            return newest();
        }
        Map.Entry<Key, Integer> match = formats.floorEntry(key);
        return match == null ? formats.firstEntry().getValue() : match.getValue();
    }

    private static Key key(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(version.trim());
        return matcher.find() ? new Key(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))) : null;
    }
}
