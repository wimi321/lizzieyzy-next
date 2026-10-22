package featurecat.lizzie.enginegame;

/**
 * Current game's analysis parameters per participant position, separate from the batch's frozen
 * opening settings. A confirmed value is reported only after the participant's readback matched.
 */
public record EngineGameParameterState(
    Position first, Position second, boolean pending, String failure) {

  /** Breadth and aggressiveness of one participant position. */
  public record Position(Value wrn, Value pda) {}

  /**
   * @param supported whether the participant accepts runtime changes of this parameter
   * @param confirmed confirmed value, or null while unknown
   * @param target requested value while it differs from the confirmed value, otherwise null
   */
  public record Value(boolean supported, Double confirmed, Double target) {}

  public Position position(boolean firstPosition) {
    return firstPosition ? first : second;
  }

  /** A failed update leaves a target unmet; the game stays paused until an apply succeeds. */
  public boolean unresolved() {
    return failure != null;
  }
}
