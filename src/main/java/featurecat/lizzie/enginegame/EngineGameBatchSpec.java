package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Immutable accepted-boundary input for one engine-game batch. Product values only. */
public record EngineGameBatchSpec(
    EngineParticipantIdentity first,
    EngineParticipantIdentity second,
    EngineGamePlayMode playMode,
    EngineGameSideLimits firstLimits,
    EngineGameSideLimits secondLimits,
    double komi,
    int handicap,
    EngineGameOpeningPlan opening,
    boolean exchangeColors,
    boolean batch,
    int initialBatchLimit,
    boolean maxMoveLimitEnabled,
    int maxMoves,
    EngineGameOutputChoices output,
    featurecat.lizzie.analysis.KataGoRules matchRules,
    EngineGameOpeningParameters openingParameters) {
  public EngineGameBatchSpec {
    first = Objects.requireNonNull(first, "first");
    second = Objects.requireNonNull(second, "second");
    playMode = Objects.requireNonNull(playMode, "playMode");
    firstLimits = Objects.requireNonNull(firstLimits, "firstLimits");
    secondLimits = Objects.requireNonNull(secondLimits, "secondLimits");
    opening = Objects.requireNonNull(opening, "opening");
    output = Objects.requireNonNull(output, "output");
    matchRules = Objects.requireNonNull(matchRules, "matchRules");
    openingParameters = Objects.requireNonNull(openingParameters, "openingParameters");
  }
}
