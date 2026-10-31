package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanThinkTime;
import featurecat.lizzie.training.OpponentPreset;
import featurecat.lizzie.training.RankReference;
import featurecat.lizzie.training.TrainingMode;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import org.junit.jupiter.api.Test;

class CoachSetupFormTest {
  private final AtomicInteger resizes = new AtomicInteger();

  private CoachSetupForm form(boolean fromCurrentAvailable) {
    CoachSetupForm form =
        new CoachSetupForm(
            (key, fallback) -> fallback, fromCurrentAvailable, resizes::incrementAndGet);
    form.setSize(form.getPreferredSize());
    form.doLayout();
    return form;
  }

  @Test
  void aNewSheetStartsAtThreeDanInLiveFeedbackAtTheNaturalPace() {
    HumanSlTrainingConfig config = form(false).config();

    assertEquals("rank_3d", config.humanSlProfile());
    assertEquals(TrainingMode.LIVE_FEEDBACK, config.mode);
    assertEquals(HumanThinkTime.Pace.NATURAL, config.pace);
    assertEquals(OpponentPreset.RANK, config.opponentPreset);
    assertEquals(10, config.moveTimeSeconds);
    assertEquals(7.5, config.komi);
  }

  @Test
  void theLastGamesSettingsComeBack() {
    CoachSetupForm form = form(true);
    HumanSlTrainingConfig saved =
        HumanSlTrainingConfig.builder()
            .mode(TrainingMode.POST_GAME_REVIEW)
            .rank(12, false)
            .playerColor(HumanSlTrainingConfig.PlayerColor.WHITE)
            .pace(HumanThinkTime.Pace.SLOW)
            .build();

    form.restore(saved, true);
    HumanSlTrainingConfig config = form.config();

    assertEquals("rank_12k", config.humanSlProfile());
    assertEquals(TrainingMode.POST_GAME_REVIEW, config.mode);
    assertEquals(HumanSlTrainingConfig.PlayerColor.WHITE, config.playerColor);
    assertEquals(HumanThinkTime.Pace.SLOW, config.pace);
    assertTrue(form.isMoreVisible());
  }

  @Test
  void aHandicapMeansThePlayerTakesBlackAndHalfAPointKomi() {
    CoachSetupForm form = form(false);
    form.restore(
        HumanSlTrainingConfig.builder()
            .playerColor(HumanSlTrainingConfig.PlayerColor.WHITE)
            .handicap(4)
            .build(),
        true);

    HumanSlTrainingConfig config = form.config();

    assertEquals(4, config.handicap);
    assertEquals(HumanSlTrainingConfig.PlayerColor.BLACK, config.playerColor);
    assertEquals(0.5, config.komi);
  }

  @Test
  void theArrowKeysMoveTheOpponentAlongTheScale() {
    CoachSetupForm form = form(false);
    CoachSetupWidgets.RankSlider slider = componentOf(form, CoachSetupWidgets.RankSlider.class);

    press(slider, KeyEvent.VK_RIGHT);
    assertEquals("rank_4d", form.config().humanSlProfile());
    press(slider, KeyEvent.VK_END);
    assertEquals("rank_9d", form.config().humanSlProfile());
    press(slider, KeyEvent.VK_HOME);
    assertEquals("rank_20k", form.config().humanSlProfile());
  }

  @Test
  void startingFromTheCurrentPositionNeedsAPositionOnTheBoard() {
    CoachSetupForm empty = form(false);
    empty.selectFromCurrentPosition();
    assertFalse(empty.config().fromCurrentPosition);

    CoachSetupForm withMoves = form(true);
    withMoves.selectFromCurrentPosition();
    assertTrue(withMoves.config().fromCurrentPosition);
    assertTrue(withMoves.isMoreVisible(), "the switch is shown where it was turned on");
  }

  @Test
  void openingMoreSettingsGrowsTheSheetAndAsksTheDialogToFit() {
    CoachSetupForm form = form(false);
    int closed = form.getPreferredSize().height;

    JComponent header = null;
    for (Component child : form.getComponents()) {
      if (child instanceof javax.swing.JButton
          && "More settings".equals(((javax.swing.JButton) child).getText())) {
        header = (JComponent) child;
      }
    }
    assertNotNull(header);
    ((javax.swing.JButton) header).doClick();

    assertTrue(form.isMoreVisible());
    assertTrue(form.getPreferredSize().height > closed);
    assertEquals(1, resizes.get());
  }

  @Test
  void everyControlHasANameForScreenReadersAndNoneOverlap() {
    CoachSetupForm form = form(true);
    form.restore(HumanSlTrainingConfig.builder().build(), true);
    form.setSize(form.getPreferredSize());
    form.doLayout();
    List<Rectangle> shown = new ArrayList<>();
    for (Component child : form.getComponents()) {
      String name = child.getAccessibleContext().getAccessibleName();
      assertTrue(name != null && !name.isBlank(), child.getClass().getSimpleName());
      if (child.isVisible()) {
        Rectangle bounds = child.getBounds();
        assertTrue(bounds.width > 0 && bounds.height > 0, name);
        assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= form.getWidth(), name);
        for (Rectangle other : shown) {
          assertFalse(bounds.intersects(other), name + " overlaps " + other);
        }
        shown.add(bounds);
      }
    }
  }

  @Test
  void theRankTableLinkFollowsTheSliderAndFallsBackBelowTenKyu() {
    CoachSetupForm form = form(false);
    CoachSetupWidgets.RankSlider slider = componentOf(form, CoachSetupWidgets.RankSlider.class);
    CoachRankTable.Link link = componentOf(form, CoachRankTable.Link.class);

    assertEquals("≈ Fox/Tygem 3D · Amateur 3 dan", link.getText());
    press(slider, KeyEvent.VK_END);
    assertEquals("≈ Fox/Tygem 9D · Amateur 6 dan", link.getText());
    press(slider, KeyEvent.VK_HOME);
    assertEquals("Rank table", link.getText(), "the table stops at 10 kyu");
  }

  @Test
  void theRankTablePicksOutTheChosenOpponentAndUsesTheLocalizedNames() {
    CoachRankTable.Sheet sheet = new CoachRankTable.Sheet((key, fallback) -> fallback, 3, true);
    RankReference.Row picked = RankReference.ROWS.get(sheet.highlightedRow());
    assertEquals("3D", picked.fox());
    assertEquals(
        -1, new CoachRankTable.Sheet((key, fallback) -> fallback, 15, false).highlightedRow());

    java.util.Map<String, String> zh =
        java.util.Map.of(
            "HumanSlTraining.rank.danValue", "{0}段",
            "HumanSlTraining.rankTable.strong", "强{0}",
            "HumanSlTraining.rankTable.amateurDan", "业余{0}段");
    java.util.function.BinaryOperator<String> text =
        (key, fallback) -> zh.getOrDefault(key, fallback);
    RankReference.Row strongEight = RankReference.ROWS.get(5);
    assertEquals("强8段", CoachRankTable.rank(text, strongEight));
    assertEquals("业余5段", CoachRankTable.grade(text, strongEight));
  }

  @Test
  void theDefaultTagStandsOutOnASelectedCardInBothThemes() {
    for (CoachSetupWidgets.Palette palette :
        List.of(CoachSetupWidgets.Palette.LIGHT, CoachSetupWidgets.Palette.DARK)) {
      assertFalse(palette.tagFill().equals(palette.selectedFill()), palette.toString());
    }
  }

  private static void press(Component target, int key) {
    for (java.awt.event.KeyListener listener : target.getKeyListeners()) {
      listener.keyPressed(
          new KeyEvent(target, KeyEvent.KEY_PRESSED, 0L, 0, key, KeyEvent.CHAR_UNDEFINED));
    }
  }

  private static <T> T componentOf(CoachSetupForm form, Class<T> type) {
    for (Component child : form.getComponents()) {
      if (type.isInstance(child)) {
        return type.cast(child);
      }
    }
    throw new AssertionError(type.getSimpleName());
  }
}
