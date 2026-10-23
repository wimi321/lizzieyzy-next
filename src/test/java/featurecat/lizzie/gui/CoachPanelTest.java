package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoachPanelTest {
  @Test
  void buttonsNeverTakeKeyboardFocusSoSpaceStaysWithTheBoard() {
    CoachPanel panel = new CoachPanel(null, () -> null);

    for (CoachButton button : panel.buttons()) {
      assertFalse(button.isFocusable(), button.getText());
    }
  }

  @Test
  void actionsOfferTakeBackPassResignAndEndGame() {
    CoachPanel panel = new CoachPanel(null, () -> null);
    List<String> texts = new ArrayList<>();
    for (CoachButton button : panel.buttons()) {
      texts.add(button.getText());
    }

    assertTrue(texts.contains(Lizzie.resourceBundle.getString("HumanSlTraining.takeBack")));
    assertTrue(texts.contains(Lizzie.resourceBundle.getString("HumanSlGame.btn.pass")));
    assertTrue(texts.contains(Lizzie.resourceBundle.getString("HumanSlGame.btn.resign")));
    assertTrue(texts.contains(Lizzie.resourceBundle.getString("CoachPanel.finish")));
  }

  @Test
  void thePanelStaysHiddenUntilAGameAttaches() {
    CoachPanel panel = new CoachPanel(null, () -> null);

    assertFalse(panel.isVisible());
    panel.detach(null);
    assertFalse(panel.isVisible());
  }

  @Test
  void sentencesJoinWithASpaceOnlyOutsideChineseAndJapanese() {
    assertEquals(
        "Same move as the AI. 41% play it.",
        CoachPanelState.join("Same move as the AI.", "41% play it."));
    assertEquals(
        "和 AI 推荐一致。同水平棋手 41% 会这样下。", CoachPanelState.join("和 AI 推荐一致。", "同水平棋手 41% 会这样下。"));
    assertEquals("和 AI 推荐一致。", CoachPanelState.join("和 AI 推荐一致。", ""));
  }

  @Test
  void aMistakeIsExplainedByTheWinChanceOrOnceDecidedByPoints() {
    java.util.function.BinaryOperator<String> text = (key, fallback) -> fallback;
    featurecat.lizzie.training.MoveFeedback open =
        new featurecat.lizzie.training.MoveFeedback(
            5,
            "A1",
            featurecat.lizzie.training.MoveVerdict.BLUNDER,
            0.47,
            0.23,
            13.7,
            0.001,
            false,
            "Q16",
            0.2);
    featurecat.lizzie.training.MoveFeedback decided =
        new featurecat.lizzie.training.MoveFeedback(
            201,
            "T1",
            featurecat.lizzie.training.MoveVerdict.PROBLEM,
            0.02,
            0.01,
            9.6,
            Double.NaN,
            false,
            "S2",
            0.1);

    assertEquals(
        "At 3 dan level your win chance fell from 47% to 23%. <1% of players at this level play it.",
        CoachPanelState.explanation(open, "3 dan", text));
    assertEquals(
        "The game is all but decided, so points decide: about 10 points lost.",
        CoachPanelState.explanation(decided, "3 dan", text));
    assertEquals(
        "Win chance \u221224% · about 14 points lost", CoachPanelState.change(open, text, true));
    assertEquals("\u221224%", CoachPanelState.change(open, text, false));
  }

  @Test
  void greenMeansTheWinChanceHeldOrRose() {
    featurecat.lizzie.training.MoveVerdict good = featurecat.lizzie.training.MoveVerdict.GOOD;
    featurecat.lizzie.training.MoveVerdict slow = featurecat.lizzie.training.MoveVerdict.SLOW;

    assertEquals(CoachPaint.verdictColor(good), CoachPaint.changeColor(good, "+1%"));
    assertEquals(CoachPanel.MUTED, CoachPaint.changeColor(good, "\u22123%"));
    assertEquals(
        CoachPanel.MUTED, CoachPaint.changeColor(good, "Win chance \u22123%"), "on the card");
    assertEquals(CoachPaint.verdictColor(slow), CoachPaint.changeColor(slow, "\u22126%"));
  }

  @Test
  void hiddenButtonsLeaveNoEmptyRowAboveEndGame() {
    CoachPanel panel = new CoachPanel(null, () -> null);
    java.awt.Container content = (java.awt.Container) componentOf(panel, CoachPanelContent.class);
    java.awt.Container actions =
        (java.awt.Container) content.getComponent(content.getComponentCount() - 1);
    java.awt.Container grid = (java.awt.Container) actions.getComponent(0);

    assertEquals(3, grid.getComponentCount(), "take back, pass and resign; no hint or retry yet");
    for (java.awt.Component button : grid.getComponents()) {
      assertTrue(button.isVisible());
    }
  }

  @Test
  void onlyTheKeyMistakesAfterTheGameAreClickable() {
    CoachPanelState.Row row =
        new CoachPanelState.Row(
            31, "#31", "A1", featurecat.lizzie.training.MoveVerdict.BLUNDER, "Blunder", "-25%");
    int[] picked = {0};
    for (boolean over : new boolean[] {false, true}) {
      CoachPanelState state =
          stateWithHistory(new CoachPanelState.History("h", List.of(), List.of(row), over));
      CoachHistoryView view =
          new CoachHistoryView(() -> state, moveNumber -> picked[0] = moveNumber);
      view.setSize(CoachPanel.WIDTH, 400);
      int firstRow = CoachHistoryView.TALLY_HEIGHT + 12 + CoachHistoryView.HEADING_HEIGHT + 5;

      assertEquals(over ? row : null, view.rowAt(firstRow), "over: " + over);
      assertEquals(null, view.rowAt(5), "the tallies are not a row");
    }
  }

  private static CoachPanelState stateWithHistory(CoachPanelState.History history) {
    return new CoachPanelState(
        "",
        "",
        "",
        CoachPanelState.Tone.DONE,
        false,
        "",
        "",
        "",
        "",
        "",
        null,
        null,
        null,
        null,
        history);
  }

  @Test
  void theHistoryShowsAsManyRecentMovesAsFitAboveTheButtons() {
    int threeRows =
        CoachHistoryView.TALLY_HEIGHT
            + 12
            + CoachHistoryView.HEADING_HEIGHT
            + 3 * CoachHistoryView.ROW_HEIGHT;

    assertEquals(0, CoachHistoryView.rowsThatFit(CoachHistoryView.TALLY_HEIGHT, 8));
    assertEquals(3, CoachHistoryView.rowsThatFit(threeRows, 8));
    assertEquals(2, CoachHistoryView.rowsThatFit(threeRows, 2));
  }

  @Test
  void theButtonsSitAtTheBottomAndTheHistoryFillsTheSpaceAboveThem() {
    CoachPanel panel = new CoachPanel(null, () -> null);
    panel.setSize(CoachPanel.WIDTH, 900);
    panel.doLayout();
    java.awt.Component summary = componentOf(panel, CoachSummaryView.class);
    java.awt.Component history = componentOf(panel, CoachHistoryView.class);
    java.awt.Container content = (java.awt.Container) componentOf(panel, CoachPanelContent.class);
    java.awt.Component actions = content.getComponent(content.getComponentCount() - 1);

    assertEquals(900 - 18, actions.getY() + actions.getHeight());
    assertTrue(history.getY() >= summary.getY() + summary.getHeight());
    assertEquals(actions.getY() - 12, history.getY() + history.getHeight());
  }

  private static java.awt.Component componentOf(java.awt.Container panel, Class<?> type) {
    for (java.awt.Component component : panel.getComponents()) {
      if (type.isInstance(component)) {
        return component;
      }
      if (component instanceof java.awt.Container nested) {
        java.awt.Component found = componentOf(nested, type);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  void focusLayoutHidesTheSidePanelsAndRestoresThemExactly() throws Exception {
    Config config = allocate(Config.class);
    config.showSubBoard = true;
    config.showCaptured = false;
    config.showVariationGraph = true;
    config.showComment = true;
    config.showStatus = true;
    config.showListPane = false;
    RecordingFrame frame = allocate(RecordingFrame.class);
    frame.calls = new ArrayList<>();
    CoachFocusLayout layout = new CoachFocusLayout();

    layout.enter(frame, config);
    layout.enter(frame, config);

    assertTrue(layout.isActive());
    assertFalse(config.showSubBoard);
    assertFalse(config.showVariationGraph);
    assertFalse(config.showComment);
    assertFalse(config.showStatus);
    assertTrue(frame.calls.contains("varTree false"));
    assertTrue(frame.calls.contains("hideComment"));

    frame.calls.clear();
    layout.exit(frame, config);
    layout.exit(frame, config);

    assertFalse(layout.isActive());
    assertTrue(config.showSubBoard);
    assertFalse(config.showCaptured);
    assertTrue(config.showVariationGraph);
    assertTrue(config.showComment);
    assertTrue(config.showStatus);
    assertFalse(config.showListPane);
    assertEquals(
        List.of("varTree true", "showComment", "refreshContainer", "refresh"), frame.calls);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }

  /** A frame that records what the focus layout asks of it; allocated without a constructor. */
  static class RecordingFrame extends LizzieFrame {
    List<String> calls;

    @Override
    public void setHideListScrollpane(boolean visible) {
      calls.add("list " + visible);
    }

    @Override
    public void setVarTreeVisible(boolean visible) {
      calls.add("varTree " + visible);
    }

    @Override
    public void hideCommentPanel() {
      calls.add("hideComment");
    }

    @Override
    public void setCommentPaneContent() {
      calls.add("showComment");
    }

    @Override
    public void refreshContainer() {
      calls.add("refreshContainer");
    }

    @Override
    public void refresh() {
      calls.add("refresh");
    }
  }
}
