package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.KataGoRules;
import java.util.List;

/** Package-visible plan factory for owner occupancy and start tests. */
public final class EngineGamePlans {
  private EngineGamePlans() {}

  public static EngineGamePlan harness(int blackIndex, int whiteIndex, boolean genmove) {
    return harness(blackIndex, whiteIndex, blackIndex, whiteIndex, genmove);
  }

  public static EngineGamePlan harness(
      int blackIndex, int whiteIndex, int firstIndex, int secondIndex, boolean genmove) {
    return harness(
        blackIndex,
        whiteIndex,
        firstIndex,
        secondIndex,
        genmove,
        EngineGameOpeningParameters.NEUTRAL);
  }

  public static EngineGamePlan harness(
      int blackIndex,
      int whiteIndex,
      int firstIndex,
      int secondIndex,
      boolean genmove,
      EngineGameOpeningParameters opening) {
    EngineParticipantIdentity black = new EngineParticipantIdentity("black", "black");
    EngineParticipantIdentity white = new EngineParticipantIdentity("white", "white");
    EngineGameSideLimits limits =
        new EngineGameSideLimits(
            EngineGameTimeMode.FIXED, 0, "", 0, 0, new EngineGameResignPolicy(0, 2, 0.0));
    return new EngineGamePlan(
        black,
        white,
        blackIndex,
        whiteIndex,
        firstIndex,
        secondIndex,
        genmove ? EngineGamePlayMode.GENMOVE : EngineGamePlayMode.ANALYSIS,
        limits,
        limits,
        7.5,
        0,
        List.of(),
        -1,
        false,
        false,
        1,
        false,
        1,
        false,
        0,
        new EngineGameOutputChoices(false, false, ""),
        KataGoRules.parse("chinese").orElseThrow(),
        opening);
  }
}
