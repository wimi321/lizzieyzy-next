package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.training.CoachLevel;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanThinkTime;
import featurecat.lizzie.training.OpponentPreset;
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
            (key, fallback) -> fallback,
            new CoachSetupContext(fromCurrentAvailable, 7.5),
            resizes::incrementAndGet);
    form.setSize(form.getPreferredSize());
    form.doLayout();
    return form;
  }

  @Test
  void aNewSheetStartsAtThreeDanInLiveFeedbackAtTheNaturalPace() {
    HumanSlTrainingConfig config = form(false).config();

    assertEquals(CoachLevel.DAN_3, config.level);
    assertEquals("rank_3k", config.humanSlProfile(), "3 dan is rank_3k with search");
    assertEquals(TrainingMode.LIVE_FEEDBACK, config.mode);
    assertEquals(HumanThinkTime.Pace.NATURAL, config.pace);
    assertEquals(OpponentPreset.RANK, config.opponentPreset);
    assertEquals(10, config.moveTimeSeconds);
    assertEquals(7.5, config.komi);
  }

  @Test
  void anEvenGameUsesTheKomiOfTheMainWindowsRules() {
    CoachSetupForm form =
        new CoachSetupForm(
            (key, fallback) -> fallback,
            new CoachSetupContext(false, 7.0),
            resizes::incrementAndGet);

    assertEquals(7.0, form.config().komi, "the 2027 rules");
    assertEquals("7", CoachSetupForm.komiText(7.0));
    assertEquals("6.5", CoachSetupForm.komiText(6.5));
  }

  @Test
  void theLastGamesSettingsComeBack() {
    CoachSetupForm form = form(true);
    HumanSlTrainingConfig saved =
        HumanSlTrainingConfig.builder()
            .mode(TrainingMode.POST_GAME_REVIEW)
            .level(CoachLevel.STRONG_DAN_4)
            .playerColor(HumanSlTrainingConfig.PlayerColor.WHITE)
            .pace(HumanThinkTime.Pace.SLOW)
            .build();

    form.restore(saved, true);
    HumanSlTrainingConfig config = form.config();

    assertEquals(CoachLevel.STRONG_DAN_4, config.level);
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
    assertEquals(CoachLevel.STRONG_DAN_3, form.config().level, "the 强 half step comes next");
    press(slider, KeyEvent.VK_END);
    assertEquals(CoachLevel.DAN_11, form.config().level);
    press(slider, KeyEvent.VK_HOME);
    assertEquals(CoachLevel.KYU_10, form.config().level);
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
  void everyLevelOfTheTableCanBeChosenOnTheSlider() {
    CoachSetupForm form = form(false);
    CoachSetupWidgets.RankSlider slider = componentOf(form, CoachSetupWidgets.RankSlider.class);

    for (CoachLevel level : CoachLevel.values()) {
      slider.setValue(level.ordinal(), true);
      assertEquals(level, form.config().level);
    }
    assertEquals("Rank table", componentOf(form, CoachRankTable.Link.class).getText());
  }

  @Test
  void theTicksAreLabelledWithFoxRanks() {
    CoachSetupWidgets.RankSlider slider =
        componentOf(form(false), CoachSetupWidgets.RankSlider.class);
    List<String> labels = new ArrayList<>();
    for (int tick : slider.ticks()) {
      labels.add(CoachLevel.at(tick).fox());
    }

    assertEquals(List.of("17-18K", "7-8K", "1D", "3D", "5D", "7D", "9D", "10D"), labels);
  }

  @Test
  void theRankTablePicksOutTheChosenOpponentAndAClickPlaysARow() {
    List<CoachLevel> picked = new ArrayList<>();
    CoachRankTable.Sheet sheet =
        new CoachRankTable.Sheet((key, fallback) -> fallback, CoachLevel.DAN_3, picked::add);
    List<CoachLevel> rows = CoachRankTable.Sheet.strongestFirst();

    assertEquals(CoachLevel.DAN_11, rows.get(0));
    assertEquals(CoachLevel.DAN_3, rows.get(sheet.highlightedRow()));
    assertEquals(-1, sheet.rowAt(5), "the header is not a row");

    int strongTenRow = rows.indexOf(CoachLevel.STRONG_DAN_10);
    sheet.pick(sheet.rowAt((strongTenRow + 1) * 26 + 13));
    assertEquals(List.of(CoachLevel.STRONG_DAN_10), picked);
  }

  @Test
  void theOpponentGoesByItsFoxRankWithTheKataGoLevelBeside() {
    java.util.Map<String, String> zh =
        java.util.Map.of(
            "HumanSlTraining.level.fox", "野狐 {0}",
            "HumanSlTraining.level.detail", "KataGo {0} · 约{1}",
            "HumanSlTraining.rank.danValue", "{0}段",
            "HumanSlTraining.rankTable.strong", "强{0}",
            "HumanSlTraining.rankTable.amateurDan", "业余{0}段",
            "HumanSlTraining.rankTable.topAmateur", "业余顶尖");
    java.util.function.BinaryOperator<String> text =
        (key, fallback) -> zh.getOrDefault(key, fallback);

    assertEquals("野狐 8-9D", CoachLevelText.fox(text, CoachLevel.STRONG_DAN_8));
    assertEquals("强8段", CoachLevelText.kataGo(text, CoachLevel.STRONG_DAN_8));
    assertEquals("业余5段", CoachLevelText.grade(text, CoachLevel.STRONG_DAN_8));
    // 9 dan and 强9段 share a Fox rank; the detail tells them apart.
    assertEquals(
        CoachLevelText.fox(text, CoachLevel.DAN_9),
        CoachLevelText.fox(text, CoachLevel.STRONG_DAN_9));
    assertEquals("KataGo 9段 · 约业余6段", CoachLevelText.detail(text, CoachLevel.DAN_9));
    assertEquals("KataGo 强9段 · 约业余顶尖", CoachLevelText.detail(text, CoachLevel.STRONG_DAN_9));
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
