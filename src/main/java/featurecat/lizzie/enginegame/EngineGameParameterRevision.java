package featurecat.lizzie.enginegame;

/** Admission result of one game-parameter request; nothing is written unless ACCEPTED. */
public enum EngineGameParameterRevision {
  /** The latest target was recorded and will be applied in order. */
  ACCEPTED,
  /** Every requested value already matches the confirmed value. */
  UNCHANGED,
  /** No analysis-mode engine game is playing. */
  NOT_PLAYING,
  /** A requested participant or parameter cannot be changed during the game. */
  UNSUPPORTED,
  /** A value is not finite or out of range. */
  INVALID
}
