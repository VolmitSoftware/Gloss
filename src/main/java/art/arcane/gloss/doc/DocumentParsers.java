package art.arcane.gloss.doc;

import art.arcane.volmlib.util.bukkit.json.BukkitJson;

public final class DocumentParsers {
    private DocumentParsers() {
    }

    public static <T> T parseJson(String fileName, String raw, Class<T> type) {
        try {
            T value = BukkitJson.GSON.fromJson(raw, type);
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
