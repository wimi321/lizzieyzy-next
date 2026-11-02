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
            .level(CoachLevel.STRONG_DAN_7)
            .playerColor(PlayerColor.WHITE)
            .pace(HumanThinkTime.Pace.SLOW)
            .handicap(2)
            .komi(0.5)
            .fromCurrentPosition(true)
            .build();

    HumanSlTrainingPreferences.store(ui, selected, true);
    HumanSlTrainingPreferences.SavedSettings restored = HumanSlTrainingPreferences.load(ui);

    assertEquals(42, ui.getInt("unrelated-setting"));
    assertEquals(TrainingMode.LIVE_ANALYSIS, restored.config().mode);
    assertEquals(OpponentPreset.RANK, restored.config().opponentPreset);
    assertEquals(CoachLevel.STRONG_DAN_7, restored.config().level);
    assertEquals(PlayerColor.WHITE, restored.config().playerColor);
    assertEquals(HumanThinkTime.Pace.SLOW, restored.config().pace);
    assertEquals(10, restored.config().moveTimeSeconds, "the search budget is not a setting");
    assertEquals(2, restored.config().handicap);
    assertEquals(0.5, restored.config().komi);
    assertFalse(restored.config().fromCurrentPosition);
    assertTrue(restored.advancedVisible());
  }

  @Test
  void missingSettingsUseTheProductDefaults() {
    HumanSlTrainingPreferences.SavedSettings restored =
        HumanSlTrainingPreferences.load(new JSONObject());

    assertEquals(TrainingMode.LIVE_FEEDBACK, restored.config().mode);
    assertEquals(OpponentPreset.RANK, restored.config().opponentPreset);
    assertEquals(CoachLevel.DAN_3, restored.config().level);
    assertEquals(PlayerColor.RANDOM, restored.config().playerColor);
    assertEquals(10, restored.config().moveTimeSeconds);
    assertEquals(0, restored.config().handicap);
    assertEquals(7.5, restored.config().komi);
    assertFalse(restored.advancedVisible());
  }

  @Test
  void aSavedChoiceOfAnOlderModeIsKept() {
    JSONObject saved = new JSONObject().put("mode", "POST_GAME_REVIEW");
    JSONObject ui = new JSONObject().put(HumanSlTrainingPreferences.UI_CONFIG_KEY, saved);

    assertEquals(TrainingMode.POST_GAME_REVIEW, HumanSlTrainingPreferences.load(ui).config().mode);
  }

  @Test
  void liveFeedbackRoundTrips() {
    JSONObject ui = new JSONObject();
    HumanSlTrainingPreferences.store(
        ui, HumanSlTrainingConfig.builder().mode(TrainingMode.LIVE_FEEDBACK).build(), false);

    assertEquals(TrainingMode.LIVE_FEEDBACK, HumanSlTrainingPreferences.load(ui).config().mode);
  }

  @Test
  void thePaceRoundTrips() {
    JSONObject ui = new JSONObject();
    HumanSlTrainingPreferences.store(
        ui, HumanSlTrainingConfig.builder().pace(HumanThinkTime.Pace.FAST).build(), false);

    assertEquals(HumanThinkTime.Pace.FAST, HumanSlTrainingPreferences.load(ui).config().pace);
  }

  @Test
  void theOldTimeSettingBecomesAPace() {
    assertEquals(HumanThinkTime.Pace.NATURAL, paceAfterSaving(10));
    assertEquals(HumanThinkTime.Pace.NATURAL, paceAfterSaving(30));
    assertEquals(HumanThinkTime.Pace.SLOW, paceAfterSaving(60));
    assertEquals(HumanThinkTime.Pace.SLOW, paceAfterSaving(24 * 60 * 60));
  }

  private static HumanThinkTime.Pace paceAfterSaving(int moveTimeSeconds) {
    JSONObject saved = new JSONObject().put("move-time-seconds", moveTimeSeconds);
    JSONObject ui = new JSONObject().put(HumanSlTrainingPreferences.UI_CONFIG_KEY, saved);
    return HumanSlTrainingPreferences.load(ui).config().pace;
  }

  @Test
  void theRetiredProPresetsBecomeNineDan() {
    for (String preset : new String[] {"MODERN_PRO", "ONLINE_9D"}) {
      JSONObject saved =
          new JSONObject().put("opponent-preset", preset).put("rank", 2).put("dan-rank", false);
      JSONObject ui = new JSONObject().put(HumanSlTrainingPreferences.UI_CONFIG_KEY, saved);

      HumanSlTrainingConfig config = HumanSlTrainingPreferences.load(ui).config();

      assertEquals(OpponentPreset.RANK, config.opponentPreset, preset);
      assertEquals(CoachLevel.DAN_9, config.level, preset);
    }
  }

  @Test
  void aRankSavedBeforeTheLevelsBecomesThePlainLevelOfThatNumber() {
    assertEquals(CoachLevel.DAN_3, levelAfterSaving(3, true));
    assertEquals(CoachLevel.DAN_9, levelAfterSaving(9, true));
    assertEquals(CoachLevel.KYU_5, levelAfterSaving(5, false));
    assertEquals(CoachLevel.KYU_10, levelAfterSaving(10, false));
    assertEquals(
        CoachLevel.KYU_10, levelAfterSaving(15, false), "the weakest level replaces 11-20 kyu");
  }

  @Test
  void olderVersionsStillFindTheNumberOfTheLevel() {
    JSONObject ui = new JSONObject();
    HumanSlTrainingPreferences.store(
        ui, HumanSlTrainingConfig.builder().level(CoachLevel.DAN_11).build(), false);
    JSONObject saved = ui.getJSONObject(HumanSlTrainingPreferences.UI_CONFIG_KEY);

    assertEquals("DAN_11", saved.getString("level"));
    assertEquals(9, saved.getInt("rank"), "older versions only know up to 9 dan");
    assertTrue(saved.getBoolean("dan-rank"));
  }

  private static CoachLevel levelAfterSaving(int rank, boolean dan) {
    JSONObject saved = new JSONObject().put("rank", rank).put("dan-rank", dan);
    JSONObject ui = new JSONObject().put(HumanSlTrainingPreferences.UI_CONFIG_KEY, saved);
    return HumanSlTrainingPreferences.load(ui).config().level;
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
    assertEquals(OpponentPreset.RANK, restored.config().opponentPreset);
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

    assertEquals(TrainingMode.LIVE_FEEDBACK, restored.config().mode);
    assertEquals(OpponentPreset.RANK, restored.config().opponentPreset);
    assertEquals(CoachLevel.KYU_10, restored.config().level);
    assertEquals(PlayerColor.RANDOM, restored.config().playerColor);
    assertEquals(10, restored.config().moveTimeSeconds);
    assertEquals(9, restored.config().handicap);
    assertEquals(7.5, restored.config().komi);
    assertTrue(restored.advancedVisible());
  }
}
