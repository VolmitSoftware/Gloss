package art.arcane.gloss.profile;

/**
 * What {@link PlayerHeadService#lookup(String)} could say about a name without blocking.
 *
 * @param state   which of the three answers this is
 * @param profile the account, present only when {@link State#RESOLVED}
 */
public record PlayerHeadLookup(State state, PlayerHeadProfile profile) {
  private static final PlayerHeadLookup PENDING = new PlayerHeadLookup(State.PENDING, null);
  private static final PlayerHeadLookup UNKNOWN = new PlayerHeadLookup(State.UNKNOWN, null);

  public enum State {
    /** The lookup is in flight. Render the neutral head and ask again on the next refresh. */
    PENDING,
    /** The account resolved. {@link #profile()} is non-null. */
    RESOLVED,
    /** The name does not exist, is unusable as a username, or the lookup failed. */
    UNKNOWN
  }

  public static PlayerHeadLookup pending() {
    return PENDING;
  }

  public static PlayerHeadLookup unknown() {
    return UNKNOWN;
  }

  public static PlayerHeadLookup resolved(PlayerHeadProfile profile) {
    return new PlayerHeadLookup(State.RESOLVED, profile);
  }

  public boolean isResolved() {
    return state == State.RESOLVED;
  }

  public boolean isPending() {
    return state == State.PENDING;
  }
}
