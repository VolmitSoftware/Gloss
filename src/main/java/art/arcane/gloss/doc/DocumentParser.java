package art.arcane.gloss.doc;

@FunctionalInterface
public interface DocumentParser<T> {
    T parse(String fileName, String raw);
}
