package featurecat.lizzie.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HumanSlTrainingConfigTest {

  @Test
  void defaultTrainingPlaysTheThreeDanLevel() {
    HumanSlTrainingConfig config = HumanSlTrainingConfig.builder().build();

    assertEquals(CoachLevel.DAN_3, config.level);
    assertEquals("rank_3k", config.humanSlProfile());
    assertEquals(CoachLevel.DAN_3.play(), config.play().orElseThrow());
  }

  @Test
  void rankNumbersOfOlderCallersBecomeThePlainLevelWithinTheTable() {
    HumanSlTrainingConfig weakest = HumanSlTrainingConfig.builder().rank(99, false).build();
    HumanSlTrainingConfig strongest = HumanSlTrainingConfig.builder().rank(99, true).build();

    assertEquals(CoachLevel.KYU_10, weakest.level);
    assertEquals("preaz_20k", weakest.humanSlProfile());
    assertEquals(CoachLevel.DAN_11, strongest.level);
    assertEquals("rank_9d", strongest.humanSlProfile());
  }

  @Test
  void theRetiredPresetsStillPlayByProfile() {
    assertTrue(
        HumanSlTrainingConfig.builder()
            .opponentPreset(OpponentPreset.MODERN_PRO)
            .build()
            .play()
            .isEmpty());
  }

  @Test
  void modernProPresetUsesProYearAndHigherQualityBudget() {
    HumanSlTrainingConfig config =
        HumanSlTrainingConfig.builder().opponentPreset(OpponentPreset.MODERN_PRO).build();

    assertEquals("proyear_2023", config.humanSlProfile());
    assertEquals(128, config.analysisVisits());
    assertEquals(2, config.rootSymmetries());
  }

  @Test
  void onlineNineDanPresetMapsToOfficialRankProfile() {
    HumanSlTrainingConfig config =
        HumanSlTrainingConfig.builder().opponentPreset(OpponentPreset.ONLINE_9D).build();

    assertEquals("rank_9d", config.humanSlProfile());
    assertEquals(64, config.analysisVisits());
  }

  @Test
  void withAHandicapThePlayerTakesBlackWhateverTheChosenColor() {
    HumanSlTrainingConfig handicap =
        HumanSlTrainingConfig.builder()
            .handicap(3)
            .playerColor(HumanSlTrainingConfig.PlayerColor.WHITE)
            .build();
    HumanSlTrainingConfig even =
        HumanSlTrainingConfig.builder()
            .playerColor(HumanSlTrainingConfig.PlayerColor.WHITE)
            .build();

    assertEquals(true, handicap.resolveHumanIsBlack());
    assertEquals(false, even.resolveHumanIsBlack());
  }

  @Test
  void theNaturalPaceIsTheDefault() {
    assertEquals(HumanThinkTime.Pace.NATURAL, HumanSlTrainingConfig.builder().build().pace);
  }
}
