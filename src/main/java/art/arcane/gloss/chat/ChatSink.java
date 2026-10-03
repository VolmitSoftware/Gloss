package art.arcane.gloss.chat;

/**
 * What a listener path does with a dispatch outcome. The Paper bridge retains the audience and
 * installs a renderer; the Spigot listener filters recipients and sets a format.
 */
public interface ChatSink {
    void dropped(ChatDrop reason);

    void audience(ChatDispatch dispatch);
}
