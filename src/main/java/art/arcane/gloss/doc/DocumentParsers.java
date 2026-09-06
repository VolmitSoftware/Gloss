package art.arcane.gloss.doc;

import art.arcane.gloss.api.IconArgbColor;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconTextAlignment;
import art.arcane.gloss.config.icon.IconArgbColorAdapter;
import art.arcane.gloss.config.icon.IconBillboardAdapter;
import art.arcane.gloss.config.icon.IconTextAlignmentAdapter;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.Gson;

public final class DocumentParsers {
    public static final Gson GSON = BukkitJson.GSON.newBuilder()
        .registerTypeAdapter(IconBillboard.class, new IconBillboardAdapter())
        .registerTypeAdapter(IconTextAlignment.class, new IconTextAlignmentAdapter())
        .registerTypeAdapter(IconArgbColor.class, new IconArgbColorAdapter())
        .create();

    private DocumentParsers() {
    }

    public static <T> T parseJson(String fileName, String raw, Class<T> type) {
        try {
            T value = GSON.fromJson(raw, type);
            if (value == null) {
                throw new IllegalArgumentException("document is empty");
            }
            return value;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(fileName + " " + rootMessage(failure), failure);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = null;
        while (current != null) {
            String candidate = current.getMessage();
            if (candidate != null && !candidate.isBlank()) {
                message = candidate;
            }
            current = current.getCause();
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
