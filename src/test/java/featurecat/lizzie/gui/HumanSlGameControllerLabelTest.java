package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.training.CoachLevel;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.OpponentPreset;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class HumanSlGameControllerLabelTest {
  @Test
  void theOpponentGoesByItsLocalizedFoxRank() {
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle =
          ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
      HumanSlGameController controller =
          new HumanSlGameController(
              null,
              HumanSlTrainingConfig.builder()
                  .opponentPreset(OpponentPreset.RANK)
                  .level(CoachLevel.STRONG_DAN_3)
                  .build(),
              null);

      assertEquals("野狐 3-4D", controller.opponentLabel());
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void anOlderKyuRankBecomesTheWeakestLevelInEnglish() {
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      HumanSlGameController controller =
          new HumanSlGameController(
              null,
              HumanSlTrainingConfig.builder()
                  .opponentPreset(OpponentPreset.RANK)
                  .rank(12, false)
                  .build(),
              null);

      assertEquals("Fox 17-18K", controller.opponentLabel());
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }
}
