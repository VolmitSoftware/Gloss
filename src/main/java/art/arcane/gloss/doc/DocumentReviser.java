package art.arcane.gloss.doc;

public interface DocumentReviser<T> {
    long revisionOf(T value);

    T withRevision(T value, long revision);
}
