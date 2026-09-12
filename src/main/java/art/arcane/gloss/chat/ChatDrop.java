package art.arcane.gloss.chat;

/** Why a message never reached an audience. */
public enum ChatDrop {
    /** The channel engine is not carrying chat on this server. */
    NO_CHANNEL,
    /** A prompt was waiting for this player's next line. */
    CAPTURED,
    /** The channel's filters emptied the message. */
    FILTERED,
    /** The sender is inside the channel's minimum interval. */
    TOO_FAST,
    /** The sender repeated themselves past the channel's allowance. */
    REPEAT
}
