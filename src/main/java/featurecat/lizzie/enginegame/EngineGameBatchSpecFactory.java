package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Maps parsed dialog/toolbar product values to an immutable {@link EngineGameBatchSpec}. */
public final class EngineGameBatchSpecFactory {
  private EngineGameBatchSpecFactory() {}

  public static EngineGameBatchSpec from(EngineGameParsedStart parsed) {
    Objects.requireNonNull(parsed, "parsed");
    return new EngineGameBatchSpec(
        parsed.first(),
        parsed.second(),
        parsed.genmove() ? EngineGamePlayMode.GENMOVE : EngineGamePlayMode.ANALYSIS,
        side(parsed, true),
        side(parsed, false),
        parsed.komi(),
        parsed.handicap(),
        opening(parsed),
        parsed.exchangeColors(),
        parsed.batch(),
        parsed.batchLimit(),
        parsed.maxMoveLimitEnabled(),
        parsed.maxMoves(),
        new EngineGameOutputChoices(
            parsed.autosave(), parsed.saveWinrateImage(), parsed.batchName()),
        parsed.matchRules(),
        parsed.openingParameters());
  }

  private static EngineGameSideLimits side(EngineGameParsedStart parsed, boolean first) {
    return new EngineGameSideLimits(
        first ? parsed.firstTimeMode() : parsed.secondTimeMode(),
        parsed.timeLimitEnabled()
            ? (first ? parsed.firstTimeSeconds() : parsed.secondTimeSeconds())
            : -1,
        first ? parsed.firstAdvancedTimeCommand() : parsed.secondAdvancedTimeCommand(),
        parsed.visitLimitEnabled() ? (first ? parsed.firstVisits() : parsed.secondVisits()) : -1,
        parsed.firstMoveVisitLimitEnabled()
            ? (first ? parsed.firstOpeningVisits() : parsed.secondOpeningVisits())
            : -1,
        first ? parsed.firstResign() : parsed.secondResign());
  }

  private static EngineGameOpeningPlan opening(EngineGameParsedStart parsed) {
    if (parsed.continueGame()) {
      return new EngineGameOpeningPlan.ContinuePosition(parsed.continueMoves());
    }
    if (parsed.sgfOpening()) {
      return new EngineGameOpeningPlan.SgfCatalog(
          parsed.sgfOpenings(),
          parsed.sgfRandom() ? SgfOpeningStrategy.RANDOM : SgfOpeningStrategy.SEQUENTIAL);
    }
    return new EngineGameOpeningPlan.EmptyBoard();
  }
}
