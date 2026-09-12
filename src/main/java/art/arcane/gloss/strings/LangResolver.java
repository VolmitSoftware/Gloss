package art.arcane.gloss.strings;

import java.util.List;
import java.util.UUID;

/** What {@code lang(key, args...)} calls once the scope has produced a viewer. */
@FunctionalInterface
public interface LangResolver {
    /**
     * @param viewerId the viewer whose content locale applies, or null for a viewerless surface
     * @param args     the whole call argument list, the key at index 0
     */
    String resolve(UUID viewerId, String key, List<Object> args);
}
