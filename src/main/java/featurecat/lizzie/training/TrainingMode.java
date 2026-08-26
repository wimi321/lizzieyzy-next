package featurecat.lizzie.training;

/** Controls how foreground analysis is presented during a HumanSL game. */
public enum TrainingMode {
  POST_GAME_REVIEW,
  LIVE_ANALYSIS,
  /** Compatibility value for integrations created before live correction became live analysis. */
  @Deprecated
  LIVE_CORRECTION;

  public boolean isLiveAnalysis() {
    return this == LIVE_ANALYSIS || this == LIVE_CORRECTION;
  }
}
