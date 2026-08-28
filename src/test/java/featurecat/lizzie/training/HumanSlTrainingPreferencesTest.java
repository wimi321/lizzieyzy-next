package featurecat.lizzie.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.training.HumanSlTrainingConfig.PlayerColor;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class HumanSlTrainingPreferencesTest {
  @Test
  void roundTripsEveryDurableCoachChoice() {
    JSONObject ui = new JSONObject().put("unrelated-setting", 42);
    HumanSlTrainingConfig selected =
        HumanSlTrainingConfig.builder()
            .mode(TrainingMode.LIVE_ANALYSIS)
            .opponentPreset(OpponentPreset.ONLINE_9D)
            .rank(12, false)
            .playerColor(PlayerColor.WHITE)
            .moveTimeSeconds(60)
            .handicap(2)
            .komi(0.5)
            .fromCurrentPosition(true)
            .build();

    HumanSlTrainingPreferences.store(ui, selected, true);
    HumanSlTrainingPreferences.SavedSettings restored = HumanSlTrainingPreferences.load(ui);

    assertEquals(42, ui.getInt("unrelated-setting"));
    assertEquals(TrainingMode.LIVE_ANALYSIS, restored.config().mode);
    assertEquals(OpponentPreset.ONLINE_9D, restored.config().opponentPreset);
    assertEquals(12, restored.config().rank);
    assertFalse(restored.config().danRank);
    assertEquals(PlayerColor.WHITE, restored.config().playerColor);
    assertEquals(60, restored.config().moveTimeSeconds);
    assertEquals(2, restored.config().handicap);
    assertEquals(0.5, restored.config().komi);
    assertFalse(restored.config().fromCurrentPosition);
    assertTrue(restored.advancedVisible());
  }

  @Test
  void missingSettingsUseTheProductDefaults() {
    HumanSlTrainingPreferences.SavedSettings restored =
        HumanSlTrainingPreferences.load(new JSONObject());

    assertEquals(TrainingMode.POST_GAME_REVIEW, restored.config().mode);
    assertEquals(OpponentPreset.RANK, restored.config().opponentPreset);
    assertEquals(3, restored.config().rank);
    assertTrue(restored.config().danRank);
    assertEquals(PlayerColor.RANDOM, restored.config().playerColor);
    assertEquals(10, restored.config().moveTimeSeconds);
    assertEquals(0, restored.config().handicap);
    assertEquals(7.5, restored.config().komi);
    assertFalse(restored.advancedVisible());
  }

  @Test
  void legacyLiveCorrectionSettingsRestoreAsLiveAnalysis() {
    JSONObject saved =
        new JSONObject()
            .put("mode", "LIVE_CORRECTION")
            .put("opponent-preset", "MODERN_PRO")
            .put("player-color", "BLACK");
    JSONObject ui = new JSONObject().put(HumanSlTrainingPreferences.UI_CONFIG_KEY, saved);

    HumanSlTrainingPreferences.SavedSettings restored = HumanSlTrainingPreferences.load(ui);

    assertEquals(TrainingMode.LIVE_ANALYSIS, restored.config().mode);
    assertEquals(OpponentPreset.MODERN_PRO, restored.config().opponentPreset);
    assertEquals(PlayerColor.BLACK, restored.config().playerColor);
  }

  @Test
  void corruptOrFutureValuesFallBackWithoutBreakingTheDialog() {
    JSONObject saved =
        new JSONObject()
            .put("mode", "FUTURE_MODE")
            .put("opponent-preset", "UNKNOWN_STYLE")
            .put("rank", 99)
            .put("dan-rank", false)
            .put("player-color", "PURPLE")
            .put("move-time-seconds", 17)
            .put("handicap", 99)
            .put("komi", "not-a-number")
            .put("advanced-visible", true);
    JSONObject ui = new JSONObject().put(HumanSlTrainingPreferences.UI_CONFIG_KEY, saved);

    HumanSlTrainingPreferences.SavedSettings restored = HumanSlTrainingPreferences.load(ui);

    assertEquals(TrainingMode.POST_GAME_REVIEW, restored.config().mode);
    assertEquals(OpponentPreset.RANK, restored.config().opponentPreset);
    assertEquals(20, restored.config().rank);
    assertFalse(restored.config().danRank);
    assertEquals(PlayerColor.RANDOM, restored.config().playerColor);
    assertEquals(10, restored.config().moveTimeSeconds);
    assertEquals(9, restored.config().handicap);
    assertEquals(7.5, restored.config().komi);
    assertTrue(restored.advancedVisible());
  }
}
