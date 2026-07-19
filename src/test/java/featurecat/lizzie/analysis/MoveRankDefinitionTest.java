package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class MoveRankDefinitionTest {
  @Test
  void autoUsesScoreForOrdinaryDifferencesWithoutEquatingEveryThreshold() throws Exception {
    Config config = config(MoveRankEvaluationMode.AUTO);

    assertEquals(
        MoveRankDefinition.Rank.BEST,
        MoveRankDefinition.classifyDiffs(-6, Optional.of(-0.4), config));
  }

  @Test
  void autoKeepsLargeWinrateDropAsSafetyGuard() throws Exception {
    Config config = config(MoveRankEvaluationMode.AUTO);

    assertEquals(
        MoveRankDefinition.Rank.MISTAKE,
        MoveRankDefinition.classifyDiffs(-20, Optional.of(-0.1), config));
  }

  @Test
  void autoFallsBackToWinrateWhenScoreIsUnavailable() throws Exception {
    Config config = config(MoveRankEvaluationMode.AUTO);

    assertEquals(
        MoveRankDefinition.Rank.BLUNDER,
        MoveRankDefinition.classifyDiffs(-24, Optional.empty(), config));
  }

  @Test
  void combinedPreservesLegacyMostSevereBehavior() throws Exception {
    Config config = config(MoveRankEvaluationMode.COMBINED);

    assertEquals(
        MoveRankDefinition.Rank.BLUNDER,
        MoveRankDefinition.classifyDiffs(-24, Optional.of(-0.4), config));
  }

  @Test
  void explicitSingleMetricModesIgnoreTheOtherMetric() throws Exception {
    Config scoreConfig = config(MoveRankEvaluationMode.SCORE);
    Config winrateConfig = config(MoveRankEvaluationMode.WINRATE);

    assertEquals(
        MoveRankDefinition.Rank.BEST,
        MoveRankDefinition.classifyDiffs(-24, Optional.empty(), scoreConfig));
    assertEquals(
        MoveRankDefinition.Rank.BEST,
        MoveRankDefinition.classifyDiffs(-0.5, Optional.of(-12.0), winrateConfig));
  }

  @Test
  void legacyBooleanSettingsMigrateWithoutLosingSingleMetricChoice() {
    assertEquals(
        MoveRankEvaluationMode.WINRATE,
        MoveRankEvaluationMode.fromConfig("", true, false));
    assertEquals(
        MoveRankEvaluationMode.SCORE,
        MoveRankEvaluationMode.fromConfig("", false, true));
    assertEquals(
        MoveRankEvaluationMode.AUTO,
        MoveRankEvaluationMode.fromConfig("", true, true));
    assertEquals(
        MoveRankEvaluationMode.COMBINED,
        MoveRankEvaluationMode.fromConfig("combined", false, false));
  }

  private static Config config(MoveRankEvaluationMode mode) throws Exception {
    Config config = allocate(Config.class);
    config.moveRankEvaluationMode = mode;
    config.useWinLossInMoveRank = mode.usesWinrate();
    config.useScoreLossInMoveRank = mode.usesScore();
    config.winLossThreshold1 = -1;
    config.winLossThreshold2 = -3;
    config.winLossThreshold3 = -6;
    config.winLossThreshold4 = -12;
    config.winLossThreshold5 = -24;
    config.scoreLossThreshold1 = -0.5;
    config.scoreLossThreshold2 = -1.5;
    config.scoreLossThreshold3 = -3;
    config.scoreLossThreshold4 = -6;
    config.scoreLossThreshold5 = -12;
    return config;
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }
}
