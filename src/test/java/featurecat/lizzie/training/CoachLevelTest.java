package featurecat.lizzie.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CoachLevelTest {
  @Test
  void theScaleRunsFromTenKyuToElevenDanWithEveryHalfStep() {
    CoachLevel[] levels = CoachLevel.values();

    assertEquals(31, levels.length);
    assertEquals(CoachLevel.KYU_10, levels[0]);
    assertEquals(CoachLevel.DAN_11, levels[levels.length - 1]);
    assertEquals(CoachLevel.STRONG_DAN_1, CoachLevel.at(CoachLevel.DAN_1.ordinal() + 1));
    assertEquals(CoachLevel.KYU_10, CoachLevel.at(-5));
    assertEquals(CoachLevel.DAN_11, CoachLevel.at(99));
  }

  @Test
  void eachLevelCarriesItsRowOfTheRankTable() {
    assertEquals("17-18K", CoachLevel.KYU_10.fox());
    assertEquals("1K", CoachLevel.KYU_1.fox());
    assertEquals("1-2D", CoachLevel.STRONG_DAN_1.fox());
    assertEquals("3D", CoachLevel.DAN_3.fox());
    assertEquals(CoachLevel.Grade.AMATEUR_DAN, CoachLevel.DAN_3.grade());
    assertEquals(3, CoachLevel.DAN_3.gradeNumber());
    assertEquals(6, CoachLevel.DAN_9.gradeNumber());
    assertEquals(CoachLevel.Grade.TOP_AMATEUR, CoachLevel.STRONG_DAN_9.grade());
    assertEquals(CoachLevel.Grade.PRO, CoachLevel.DAN_10.grade());
    assertEquals(CoachLevel.Grade.TOP_PRO, CoachLevel.STRONG_DAN_10.grade());
    assertEquals("10D", CoachLevel.DAN_11.fox());
    assertEquals(CoachLevel.Grade.BEYOND_TOP_PRO, CoachLevel.DAN_11.grade());
  }

  @Test
  void kyuLevelsPlayTheRawPreAlphaZeroPolicyAndDanLevelsSearch() {
    String[] kyuProfiles = {
      "preaz_20k", "preaz_18k", "preaz_16k", "preaz_14k", "preaz_12k",
      "preaz_11k", "preaz_10k", "preaz_9k", "preaz_8k", "preaz_7k"
    };
    for (int i = 0; i < kyuProfiles.length; i++) {
      HumanSlPlaySettings play = CoachLevel.at(i).play();
      assertEquals(kyuProfiles[i], play.profile());
      assertFalse(play.searches());
      assertEquals(0.85, play.temperatureEarly());
      assertEquals(0.70, play.temperature());
      assertEquals(0.01, play.temperatureOnlyBelowProb());
    }
    for (CoachLevel level : CoachLevel.values()) {
      assertEquals(level.dan(), level.play().searches(), level.name());
    }
  }

  @Test
  void aHalfStepUpToFiveDanUsesTheProfileOfTheNextDanWithLessSearch() {
    CoachLevel[][] steps = {
      {CoachLevel.STRONG_DAN_1, CoachLevel.DAN_2},
      {CoachLevel.STRONG_DAN_2, CoachLevel.DAN_3},
      {CoachLevel.STRONG_DAN_3, CoachLevel.DAN_4},
      {CoachLevel.STRONG_DAN_4, CoachLevel.DAN_5},
      {CoachLevel.STRONG_DAN_5, CoachLevel.DAN_6}
    };
    for (CoachLevel[] step : steps) {
      HumanSlPlaySettings strong = step[0].play();
      assertEquals(step[1].play().profile(), strong.profile(), step[0].name());
      assertEquals(1.25, strong.piklLambda(), step[0].name());
      assertEquals(0.1, strong.rootExploreWeightless(), step[0].name());
      assertEquals(0.01, strong.temperatureOnlyBelowProb(), step[0].name());
    }
  }

  @Test
  void strongerLevelsNeverSearchLess() {
    int visits = 0;
    for (CoachLevel level : CoachLevel.values()) {
      if (level.play().searches()) {
        assertTrue(level.play().maxVisits() >= visits, level.name());
        visits = level.play().maxVisits();
      }
    }
    assertEquals(60, CoachLevel.DAN_1.play().maxVisits());
    assertEquals(400, CoachLevel.DAN_11.play().maxVisits());
  }

  @Test
  void onlyTheTwoBiggestSearchesGetMoreTimePerMove() {
    for (CoachLevel level : CoachLevel.values()) {
      boolean big = level.play().searches() && level.play().maxVisits() >= 200;
      assertEquals(big ? 2 : 1, level.play().moveTimeFactor(), level.name());
    }
    assertEquals(2, CoachLevel.STRONG_DAN_10.play().moveTimeFactor());
    assertEquals(2, CoachLevel.DAN_11.play().moveTimeFactor());
  }

  @Test
  void theSearchOverridesAreTheConfigOfTheLevel() {
    Map<String, Object> tenDan = CoachLevel.DAN_10.play().searchOverrides();

    assertEquals("rank_7d", tenDan.get("humanSLProfile"));
    assertEquals(100, tenDan.get("maxVisits"));
    assertEquals(8, tenDan.get("numSearchThreads"));
    assertEquals(0.8, tenDan.get("humanSLChosenMovePiklLambda"));
    assertEquals(0.3, tenDan.get("humanSLRootExploreProbWeightless"));
    assertEquals(2.0, tenDan.get("humanSLCpuctPermanent"));
    assertEquals(0.5, tenDan.get("humanSLCpuctExploration"));
    assertEquals(0.7, tenDan.get("chosenMoveTemperatureEarly"));
    assertEquals(0.4, tenDan.get("chosenMoveTemperature"));
    assertEquals(1.0, tenDan.get("chosenMoveTemperatureOnlyBelowProb"));
    assertEquals(true, tenDan.get("humanSLChosenMoveIgnorePass"));
    assertEquals(2, tenDan.get("rootNumSymmetriesToSample"));
    assertEquals(false, tenDan.get("ignorePreRootHistory"));

    Map<String, Object> eightDan = CoachLevel.DAN_8.play().searchOverrides();
    assertEquals(1.0, eightDan.get("staticScoreUtilityFactor"));
    assertEquals(1.0, eightDan.get("dynamicScoreUtilityFactor"));
  }

  @Test
  void olderRankNumbersBecomeThePlainLevel() {
    assertEquals(CoachLevel.DAN_3, CoachLevel.forRank(3, true));
    assertEquals(CoachLevel.DAN_11, CoachLevel.forRank(99, true));
    assertEquals(CoachLevel.DAN_1, CoachLevel.forRank(0, true));
    assertEquals(CoachLevel.KYU_7, CoachLevel.forRank(7, false));
    assertEquals(CoachLevel.KYU_10, CoachLevel.forRank(20, false));
  }

  @Test
  void aSavedNameComesBackOrFallsBack() {
    assertEquals(CoachLevel.STRONG_DAN_7, CoachLevel.named("STRONG_DAN_7", CoachLevel.DEFAULT));
    assertEquals(CoachLevel.DEFAULT, CoachLevel.named("DAN_12", CoachLevel.DEFAULT));
    assertEquals(CoachLevel.DEFAULT, CoachLevel.named(null, CoachLevel.DEFAULT));
  }
}
